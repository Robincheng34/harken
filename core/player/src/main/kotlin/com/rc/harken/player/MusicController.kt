// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken.player

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.rc.harken.net.model.Track
import com.rc.harken.playback.PlayContext
import com.rc.harken.playback.PlaySource
import com.rc.harken.playback.RepeatMode
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
     * 播放层提示事件（iOS `PlayerService.swift:1097-1099` 的 `ToastCenter`）。
     *
     * 目前承载两条提示：
     * 1. 试听片段 →「VIP 歌曲，当前为试听片段」（由本类 [observePlaybackToasts] 判定）；
     * 2. 播放指令失败 →「操作失败，请重试」（由 [PlaybackCommands] 经 [notifyToast] 上抛）。
     * 原先还有「已使用第三方音源：X」，已按需求删除（见 [observePlaybackToasts]）；
     * [PlayerState.unblockSource] 字段本身保留，只是不再被读取来弹提示。
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

    /**
     * 由本模块内其它组件上抛一次提示（当前唯一调用方：[PlaybackCommands]）。
     *
     * [PlaybackCommands] 是独立类而非内部类，拿不到私有字段 `_playerToastEvent`；
     * 与其为它单开一条 SharedFlow（`:app` 还要多订阅一处、多解析一份），不如在这里
     * 给一个极小的下行入口——提示事件始终只有一条流、一个消费点
     * （`HarkenApp` 根可组合），文案仍留在 `strings.xml` 单一真源。
     *
     * @param messageRes `@StringRes` 文案 ID。
     * @param args 文案格式化参数（`context.getString(res, *args)`）。
     */
    fun notifyToast(messageRes: Int, args: List<String> = emptyList()) {
        // extraBufferCapacity = 2 且无订阅者时会静默丢弃——提示丢了不致命，
        // 但必须留痕，否则又是一处「静默失败」。
        val emitted = _playerToastEvent.tryEmit(PlayerToastEvent(messageRes, args))
        if (!emitted) Log.w(TAG, "提示事件缓冲已满，已丢弃 res=$messageRes")
    }

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
     * 订阅引擎状态、按 [shouldShowOneShotToast] 判定并下发试听提示。
     *
     * 只在「曲目 ID + 试听标记」这组键变化时判定，避免位置轮询（500ms 一跳）
     * 把整条状态流反复推到这里。
     *
     * 原实现在 `unblockSource != null` 时还会提示「已使用第三方音源：X」。该提示
     * 会让用户在起播瞬间看到源名，已按需求整体移除；[PlayerState.unblockSource]
     * 字段仍由 `PlaybackEngine` 写入，供后续诊断 / 展示使用，这里不再读取。
     */
    private fun observePlaybackToasts() {
        var lastTrialTrackID: Long? = null
        state
            .map { playerState -> playerState.currentTrack?.id to playerState.isTrial }
            .distinctUntilChanged()
            .onEach { (trackID, isTrial) ->
                val (nextTrialID, showTrial) =
                    shouldShowOneShotToast(lastTrialTrackID, trackID, isTrial)
                lastTrialTrackID = nextTrialID
                if (showTrial) {
                    _playerToastEvent.tryEmit(PlayerToastEvent(R.string.player_trial_toast))
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

    // MARK: - Commands

    fun play(tracks: List<Track>, startIndex: Int, context: PlayContext?, source: PlaySource) =
        engine.play(tracks, startIndex, context, source)

    fun togglePlay() = engine.togglePlay()

    /** 清空当前播放队列并停止播放（队列面板「删除当前播放队列」）。 */
    fun clearQueue() = engine.clearQueue()

    /** 队列面板左滑删除单曲。 */
    fun removeFromQueue(index: Int) = engine.removeFromQueue(index)

    /** 队列面板长按拖拽排序。 */
    fun moveInQueue(from: Int, to: Int) = engine.moveInQueue(from, to)

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
