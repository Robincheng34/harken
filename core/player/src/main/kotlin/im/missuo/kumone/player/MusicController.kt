// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.player

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import im.missuo.kumone.net.model.Track
import im.missuo.kumone.playback.PlayContext
import im.missuo.kumone.playback.PlaySource
import im.missuo.kumone.playback.RepeatMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * UI 侧唯一播放入口（架构文档 §4.1）。
 *
 * 暴露进程级引擎的 [state] 与全部指令；同时经 MediaController 绑定
 * [PlayerService]，保持会话存活并接通外部控制（通知栏/蓝牙）。
 *
 * 命令直抵引擎而非经会话中转：单进程应用下二者是同一实例，直调避免异步
 * 绑定的竞态；会话侧（通知栏按钮）由 PlayerService 的 MediaSession 自行
 * 驱动 ExoPlayer。
 */
class MusicController(private val context: Context) {

    private val appContext: Context = context.applicationContext
    private val engine: PlaybackEngine = PlaybackEngine.acquire(appContext)

    /** 播放状态单一数据源（StateFlow）。 */
    val state: StateFlow<PlayerState> = engine.state

    /**
     * 播放层提示事件（iOS `PlayerService.swift:1093-1100` 的两条 `ToastCenter`）。
     *
     * 承载两类提示：
     * - 走了第三方解锁源 → 「已使用第三方音源：X」（iOS `:1094-1095`）；
     * - 试听片段 → 「VIP 歌曲，当前为试听片段」（iOS `:1097-1099`）。
     *
     * Android 侧若把提示留在播放页里，播放页未打开时就弹不出来；因此上提到常驻的
     * MusicController，由 `:app` 根可组合统一订阅。
     *
     * 本层不是 Composable，拿不到 `stringResource`，故只上抛资源 ID 与格式化参数
     * 而不 `context.getString()`——既避免在非 UI 层固化语言，也让文案仍在
     * `strings.xml` 单一真源内。
     */
    private val _playerToastEvent: MutableSharedFlow<PlayerToastEvent> =
        MutableSharedFlow(extraBufferCapacity = 2)
    val playerToastEvent: SharedFlow<PlayerToastEvent> = _playerToastEvent.asSharedFlow()

    private val controllerScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // 绑定失败回调在完成线程上置空此字段，@Volatile 保证跨线程可见。
    @Volatile
    private var controllerFuture: ListenableFuture<MediaController>? = null

    init {
        connect()
        observePlaybackToasts()
    }

    /**
     * 订阅引擎状态、按 [shouldShowOneShotToast] 判定并下发两条播放提示。
     *
     * 只在「曲目 ID + 试听标记 + 解锁源名」这组键变化时判定，避免位置轮询
     * （500ms 一跳）把整条状态流反复推到这里。
     *
     * 试听与解锁**各持一份记忆**：iOS `:1094` 与 `:1097` 是两个独立 `if`，
     * 可以同时成立（解锁后的音源理论上不再是试听片段，但预取/预载路径两者可能
     * 共存），因此这里刻意不写成 else-if。
     */
    private fun observePlaybackToasts() {
        var lastTrialTrackID: Long? = null
        var lastUnblockTrackID: Long? = null
        state
            .map { playerState ->
                Triple(playerState.currentTrack?.id, playerState.isTrial, playerState.unblockSource)
            }
            .distinctUntilChanged()
            .onEach { (trackID, isTrial, unblockSource) ->
                val (nextTrialID, showTrial) =
                    shouldShowOneShotToast(lastTrialTrackID, trackID, isTrial)
                lastTrialTrackID = nextTrialID
                if (showTrial) {
                    _playerToastEvent.tryEmit(PlayerToastEvent(R.string.player_trial_toast))
                }

                // iOS :1094-1095：非空即命中解锁。源名为空串（宽松路径无元数据）
                // 时退化成不带名字的文案，但**仍然提示**——「是否走了第三方源」
                // 比「源叫什么」更该让用户知道。
                val (nextUnblockID, showUnblock) =
                    shouldShowOneShotToast(lastUnblockTrackID, trackID, unblockSource != null)
                lastUnblockTrackID = nextUnblockID
                if (showUnblock) {
                    val name = unblockSource?.takeIf { it.isNotBlank() }
                    _playerToastEvent.tryEmit(
                        if (name == null) {
                            PlayerToastEvent(R.string.player_unblock_toast_unnamed)
                        } else {
                            PlayerToastEvent(R.string.player_unblock_toast, listOf(name))
                        },
                    )
                }
            }
            .launchIn(controllerScope)
    }

    /** 绑定播放服务（幂等）。 */
    fun connect() {
        if (controllerFuture != null) return
        val token = SessionToken(appContext, ComponentName(appContext, PlayerService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        controllerFuture = future
        // 原 bug：future 赋值后无人监听，绑定失败被吞且 connect() 因非空永远 no-op。
        // 失败时置空 controllerFuture（以身份比较保护，不误伤重绑后的新 future），
        // 使下次 connect() 可重试。
        future.addListener(
            {
                try {
                    future.get()
                } catch (e: Exception) {
                    Log.w(TAG, "MediaController 绑定失败，已重置以便下次 connect() 重试", e)
                    if (controllerFuture === future) controllerFuture = null
                }
            },
            { it.run() },
        )
    }

    /** 解绑；状态流在进程存活期间持续可用。 */
    fun release() {
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
    }

    // MARK: - Commands

    fun play(tracks: List<Track>, startIndex: Int, context: PlayContext?, source: PlaySource) =
        engine.play(tracks, startIndex, context, source)

    fun togglePlay() = engine.togglePlay()

    /** 清空当前播放队列并停止播放（队列面板「删除当前播放队列」）。 */
    fun clearQueue() = engine.clearQueue()

    fun next() = engine.next()

    fun previous() = engine.previous()

    fun seekTo(positionMs: Long) = engine.seekTo(positionMs)

    fun setRepeatMode(mode: RepeatMode) = engine.setRepeatMode(mode)

    fun setShuffle(enabled: Boolean) = engine.setShuffle(enabled)

    fun startFM() = engine.startFM()

    fun fmNext() = engine.fmNext()

    fun fmTrash() = engine.fmTrash()

    private companion object {
        private const val TAG = "MusicController"
    }
}
