// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.rc.harken.storage.download.LocalSongSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 播放前台服务：MediaSession 外壳 + 通知栏媒体控制。
 *
 * 播放本体是进程级单例 [PlaybackEngine]（单 Activity 应用，服务与
 * MusicController 共享）；本服务负责：
 * - 把引擎的 ExoPlayer 挂到 [MediaSession]（通知栏/蓝牙/外部控制入口）；
 * - 经 [DefaultMediaNotificationProvider] 展示媒体通知（Coil 封面）；
 * - 播放开始自动转前台（mediaPlayback 类型，见库清单合并）。
 *
 * 依赖注入：NeteaseApi / SettingsRepository / CoroutineScope 由 `:app` 的
 * AppGraph 在启动时经 [PlayerDependencies] 提供（见 T15）。
 */
class PlayerService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var bitmapLoader: CoilBitmapLoader? = null

    /**
     * 本次服务生命周期内是否已发过最小前台通知（[onStartCommand] 用）。
     *
     * 不加门控的话，每个 media button intent 都会把前台通知替换回占位通知——
     * media3 只在播放状态/元数据变化时重发媒体通知，一个不改变状态的按键
     * （如暂停态再按暂停）会留下无封面无按钮的占位通知长期占住前台位。
     */
    private var minimalForegroundPosted = false

    /** 通知栏播放控制卡片是否启用（来自 SettingsRepository，默认开）。 */
    @Volatile
    private var mediaControlsEnabled = true

    /**
     * 关闭控制条后是否已发过「极简播放提示」。
     *
     * 用来区分两种「没有内容」：①首播 URL 解析中（ExoPlayer 还是空 timeline），
     * 此时要保留 [startForegroundMinimal] 发的前台通知，否则服务掉出前台，慢网
     * 解析期间可能被系统回收；②播放结束/队列清空，此时应退出前台并撤掉通知。
     */
    private var minimalPlaybackNotificationPosted = false

    /** 只用于观察设置变化，随服务 onDestroy 取消，避免跨服务实例泄漏。 */
    private val settingScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        if (!PlayerDependencies.isProvided) {
            Log.e(TAG, "PlayerDependencies not provided yet; stopping service")
            // 原 bug：未调 startForeground 就 stopSelf()，系统判定
            // ForegroundServiceDidNotStartInTime 直接崩溃（进程被系统重启、
            // service 先于 AppGraph bootstrap 被拉起的场景）。先发一个最小通知
            // 转前台，再停止服务。
            startForegroundMinimal()
            stopSelf()
            return
        }
        val engine = PlaybackEngine.acquire(applicationContext)
        val loader = CoilBitmapLoader(this)
        bitmapLoader = loader
        // 用 QueueAwarePlayer 包一层：ExoPlayer 只装单曲，MediaSession 必须看到
        // 「有下一首」，DefaultMediaNotificationProvider 才会生成第 3 个 action；
        // 实际切歌仍由包装层转发回 PlaybackEngine 的自研队列。
        val sessionBuilder = MediaSession.Builder(this, QueueAwarePlayer(engine.exoPlayer, engine))
            .setBitmapLoader(loader)
        // 点击媒体通知/会话卡片回到 App（此前 dumpsys media_session 显示
        // launchIntent=null）；拿不到 launcher intent 时跳过，不抛异常。
        val sessionActivity = createSessionActivityPendingIntent()
        if (sessionActivity != null) {
            sessionBuilder.setSessionActivity(sessionActivity)
        }
        mediaSession = sessionBuilder.build()
        setMediaNotificationProvider(DefaultMediaNotificationProvider(this))
        // 设置变更即时生效：关闭时立刻撤掉已发布的媒体卡片，开启时立即重新发布。
        PlayerDependencies.settings?.let { repository ->
            settingScope.launch {
                repository.settings.collect { appSettings ->
                    applyMediaControlsPolicy(appSettings.mediaControlsEnabled)
                }
            }
        }
    }

    /**
     * 应用「通知栏播放控制」开关。
     *
     * 重新走一遍 [onUpdateNotification]，让 media3 重算通知；是否真正发布媒体卡片由
     * [onUpdateNotification] 的覆写决定（见那里的说明）。
     */
    private fun applyMediaControlsPolicy(enabled: Boolean) {
        if (mediaControlsEnabled == enabled) return
        mediaControlsEnabled = enabled
        val session = mediaSession ?: return
        val player = session.player
        val state = player.playbackState
        // 对齐 media3 的 shouldRunInForeground(session, startInForegroundWhenPaused=false)：
        // 只有「确实在播（含缓冲）」才要求转前台，暂停态不要强行拉前台。
        val startInForegroundRequired = player.playWhenReady &&
            (state == Player.STATE_READY || state == Player.STATE_BUFFERING)
        onUpdateNotification(session, startInForegroundRequired)
    }

    /**
     * 构造「点击媒体通知卡片回到 App 主界面」的 [PendingIntent]。
     *
     * 用系统 launcher intent 而非硬编码 Activity 类名，:core:player 无需依赖 :app。
     * 返回 null 表示包管理器暂时给不出 launch intent，调用方直接跳过设置。
     */
    private fun createSessionActivityPendingIntent(): PendingIntent? {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this,
            SESSION_ACTIVITY_REQUEST_CODE,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 前台超时兜底：`startForegroundService` 拉起本服务后，media3 要等播放器
        // 进入 BUFFERING/READY 才会 startForeground（MediaNotificationManager 的
        // 判据）——而首播的第一步是 URL 解析，慢网/解锁源逐个超时时整条链路远超
        // 系统 ~10s 时限，直接 ForegroundServiceDidNotStartInTimeException 崩溃。
        // 首个命令先以最小通知立即转前台，媒体通知随后接管（通知 ID 不同，
        // startForeground 会替换而非叠加）；之后不再重复发，避免把媒体通知顶成
        // 占位通知。仅 start（非 bind）路径会走到这里，纯绑定场景不受影响。
        if (!minimalForegroundPosted) {
            startForegroundMinimal()
            minimalForegroundPosted = true
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * 依赖缺失兜底：发一个最小前台通知以满足 startForeground 时限要求，
     * 随后由调用方立即 stopSelf。minSdk 26，NotificationChannel 可直接使用；
     * Application 侧渠道极端时序下可能尚未创建，此处就地兜底建一个。
     */
    private fun startForegroundMinimal() {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        getString(com.rc.harken.player.R.string.player_service_channel_name),
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
            }
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(com.rc.harken.player.R.string.player_service_name))
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            startForeground(MINIMAL_NOTIFICATION_ID, notification)
        } catch (error: Exception) {
            // 兜底失败只能放行 stopSelf：不捕获的话 stopSelf 不会执行，
            // 系统同样会按前台超时把进程杀掉。
            Log.w(TAG, "minimal startForeground failed", error)
        }
    }

    /**
     * 媒体通知发布入口（单参重载，media3 的默认发布标志位入口）。
     *
     * media3 1.5.1 的 `MediaSessionService.onUpdateNotification(session, bool)` 会先调
     * 本方法，再依据私有标志 `defaultMethodCalled` 决定是否交给
     * `MediaNotificationManager` 默认发布。基类本方法只有一句
     * `defaultMethodCalled = true`，因此**不调 super** 就能抑制默认发布。
     *
     * 注意：只覆写本方法不足以支持「运行期开关」——原因见 2 参重载的说明。
     */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION") // media3 的单参重载已标 @Deprecated，但它正是默认发布的标志位入口。
    override fun onUpdateNotification(session: MediaSession) {
        if (mediaControlsEnabled) {
            // 走 media3 默认：完整 MediaStyle 卡片（封面 / 上一首 / 播放暂停 / 下一首 / 进度条）。
            super.onUpdateNotification(session)
            return
        }
        postMinimalPlaybackNotification(session)
    }

    /**
     * 媒体通知发布入口（2 参重载，真正的闸门）。
     *
     * **为什么还必须覆写它**：media3 1.5.1 源码里 `defaultMethodCalled` 只在构造函数
     * 里置 `false`，被基类单参重载置 `true` 之后**永不复位**：
     * ```java
     * public void onUpdateNotification(MediaSession session, boolean startInForegroundRequired) {
     *   onUpdateNotification(session);   // 这里没有 defaultMethodCalled = false;
     *   if (defaultMethodCalled) {
     *     getMediaNotificationManager().updateNotification(session, startInForegroundRequired);
     *   }
     * }
     * ```
     * 若只覆写单参：只要开关曾经打开过一次（默认就是开），关闭时标志位仍是 `true`，
     * media3 依旧会发布控制卡片——开关会「关不掉」。所以这里直接接管 2 参重载：
     * - 开：显式交给 `super`（它经单参钩子把标志位置 true，再走默认发布）；
     * - 关：完全不进 media3 的发布路径，改发极简前台通知。
     * 这也是官方 Javadoc 推荐的「自行控制通知显示/隐藏」方式（并要求自行管理前后台）。
     */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (mediaControlsEnabled) {
            super.onUpdateNotification(session, startInForegroundRequired)
            return
        }
        postMinimalPlaybackNotification(session)
    }

    /**
     * 关闭控制条后的替代通知：无封面、无按钮、无进度条，只有歌名 / 歌手。
     *
     * 存在的唯一理由是 Android 要求后台播放必须挂前台服务通知；不给就会变成普通后台
     * 服务，被系统（vivo 尤其激进）清掉，用户会以为「关了开关播放就断」。
     *
     * 通知 ID 复用 [DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID]（1001）：
     * `startForeground` 会就地替换 media3 已发布的控制卡片，避免开关切换后两张卡并存。
     * 播放停止 / 空闲时按 media3 默认语义退出前台并撤掉通知。
     */
    private fun postMinimalPlaybackNotification(session: MediaSession) {
        val player = session.player
        val state = player.playbackState
        val ongoing = player.mediaItemCount > 0 &&
            (state == Player.STATE_READY || state == Player.STATE_BUFFERING)
        if (!ongoing) {
            // 与 media3 默认行为对齐：没有内容 / 已空闲就退出前台，不留常驻通知。
            // 但「首播解析中」还不能撤：那时 [startForegroundMinimal] 的占位通知是
            // 唯一的前台凭据，撤掉会让慢网解析期间的服务掉出前台被系统回收。
            if (minimalPlaybackNotificationPosted) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
                minimalPlaybackNotificationPosted = false
            }
            return
        }
        val metadata = player.mediaMetadata
        val title = metadata.title?.toString()
            ?: getString(com.rc.harken.player.R.string.player_service_name)
        val artist = metadata.artist?.toString()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .build()
        try {
            startForeground(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID, notification)
            minimalPlaybackNotificationPosted = true
        } catch (error: Exception) {
            // Android 12+ 后台状态下 startForeground 可能被拒；捕获后只记日志，
            // 不能抛出（否则服务崩溃、播放中断）。
            Log.w(TAG, "minimal playback notification failed", error)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // session.player 现在是 MediaSession.getPlayer() 返回的 QueueAwarePlayer
        // （PlayerWrapper 的 wrapped player）。它继承 ForwardingPlayer，下面两个
        // 字段都原样委托给真实 ExoPlayer，语义与包装前完全一致：
        //   playWhenReady  -> ExoPlayer.getPlayWhenReady()
        //   mediaItemCount -> ExoPlayer.getMediaItemCount()
        // 因此「无内容或已暂停则停服、否则后台续播」的判定无需改动。
        val session = mediaSession
        val player = session?.player
        // 无播放内容或已暂停时随任务移除停止；否则后台续播。
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        bitmapLoader?.cancel()
        bitmapLoader = null
        settingScope.cancel()
        minimalForegroundPosted = false
        // 服务已停止：复位引擎内的启动标志，下次播放才会重新拉起前台服务
        // （否则 onTaskRemoved 停服后，引擎仍以为服务活着，不再发起启动）。
        PlaybackEngine.peek()?.onServiceDestroyed()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "PlayerService"

        // 与 HarkenApplication.CHANNEL_ID 保持一致（:core:player 不能依赖 :app，
        // 只能复制常量值）。
        const val CHANNEL_ID = "harken_playback"

        // 依赖缺失兜底通知的 ID；该路径从不创建 MediaSession，与
        // DefaultMediaNotificationProvider 的通知 ID 不会冲突。
        const val MINIMAL_NOTIFICATION_ID = 1

        // 点击媒体通知卡片回 App 的 PendingIntent requestCode；本服务只发这一种
        // session activity，固定值即可。
        const val SESSION_ACTIVITY_REQUEST_CODE = 0
    }
}

/**
 * 引擎依赖注入点（由 `:app` 的 AppGraph 在 bootstrap 时填充）。
 *
 * `:core:player` 不能依赖 `:app`，故用进程级 holder 传递；未注入时服务安全
 * 停止、控制器命令得到清晰异常，绝不静默失效。
 */
object PlayerDependencies {

    @Volatile
    var api: com.rc.harken.net.api.NeteaseApi? = null
        private set

    @Volatile
    var settings: com.rc.harken.storage.settings.SettingsRepository? = null
        private set

    @Volatile
    var scope: kotlinx.coroutines.CoroutineScope? = null
        private set

    /** 离线音源（下载仓储），供 [SongUrlResolver] 优先返回本地 URI。 */
    var localSource: com.rc.harken.storage.download.LocalSongSource? = null
        private set

    val isProvided: Boolean
        get() = api != null && settings != null && scope != null

    fun provide(
        api: com.rc.harken.net.api.NeteaseApi,
        settings: com.rc.harken.storage.settings.SettingsRepository,
        scope: kotlinx.coroutines.CoroutineScope,
        localSource: com.rc.harken.storage.download.LocalSongSource? = null,
    ) {
        this.api = api
        this.settings = settings
        this.scope = scope
        this.localSource = localSource
    }

    fun requireApi(): com.rc.harken.net.api.NeteaseApi =
        api ?: error("PlayerDependencies.api not provided")

    fun requireSettings(): com.rc.harken.storage.settings.SettingsRepository =
        settings ?: error("PlayerDependencies.settings not provided")

    fun requireScope(): kotlinx.coroutines.CoroutineScope =
        scope ?: error("PlayerDependencies.scope not provided")
}
