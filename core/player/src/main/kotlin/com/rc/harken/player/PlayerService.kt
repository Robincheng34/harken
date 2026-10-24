// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.rc.harken.storage.download.LocalSongSource

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
        mediaSession = MediaSession.Builder(this, engine.exoPlayer)
            .setBitmapLoader(loader)
            .build()
        setMediaNotificationProvider(DefaultMediaNotificationProvider(this))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 前台超时兜底：`startForegroundService` 拉起本服务后，media3 要等播放器
        // 进入 BUFFERING/READY 才会 startForeground（MediaNotificationManager 的
        // 判据）——而首播的第一步是 URL 解析，慢网/解锁源逐个超时时整条链路远超
        // 系统 ~10s 时限，直接 ForegroundServiceDidNotStartInTimeException 崩溃。
        // 这里先以最小通知立即转前台，媒体通知随后接管（通知 ID 不同，
        // startForeground 会替换而非叠加）。仅 start（非 bind）路径会走到这里，
        // 纯绑定场景不会多发多余通知。
        startForegroundMinimal()
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
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
