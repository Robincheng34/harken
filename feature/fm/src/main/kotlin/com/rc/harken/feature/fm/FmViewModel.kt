// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 漫游 ViewModel（T23 `FmViewModel.kt`）。
//
// 漫游播放由 `PlaybackCommands` / `MusicController` 承载（引擎内 FM 取数与「不喜欢」
// 丢弃），本 ViewModel 只负责把引擎状态投影为 [FmUiState] 并转发动作。
//
// 收藏（红心）属于本页自己的写操作：
//  - 真源：登录后向 `song/like/get` 种子化一次，写入 [FmUiState.likedTrackIDs]；
//  - 交互：点击立刻翻转本地集合（乐观），失败回滚并通过 [likeFailures] 通知 UI 提示。
// `feature/fm` 不依赖 `feature/player`，因此不复用 `NowPlayingSocialStore`，
// 集合在本 ViewModel 内本地维护。
//
// 为可纯 JVM 单测，依赖窄接口 [FmPlaybackPort] 而非 final 的 `MusicController`；
// `MusicController` 结构上已满足该接口（`state` + 四个命令方法），由 `:app` 直接注入。

package com.rc.harken.feature.fm

import com.rc.harken.net.api.NeteaseApi
import com.rc.harken.player.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 漫游所需的最小播放能力（`MusicController` 已满足）。 */
interface FmPlaybackPort {
    val state: StateFlow<PlayerState>

    fun togglePlay()

    fun next()

    fun startFM()

    fun fmNext()

    fun fmTrash()
}

class FmViewModel(
    private val controller: FmPlaybackPort,
    private val isLoggedIn: () -> Boolean,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    /** 收藏（红心）所需的网络能力；为 null 时点击只做本地乐观并提示失败。 */
    private val api: NeteaseApi? = null,
) {
    private val _uiState: MutableStateFlow<FmUiState> = MutableStateFlow(FmUiState())
    val uiState: StateFlow<FmUiState> = _uiState.asStateFlow()

    /**
     * 收藏失败的一次性事件（失败必须有用户可见反馈）。
     *
     * 只发事件不带文案：文案由 Screen 从 `strings.xml` 取，ViewModel 不持有资源。
     */
    private val _likeFailures = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val likeFailures: SharedFlow<Unit> = _likeFailures.asSharedFlow()

    /** 红心集合是否已尝试过服务端种子化（每个 ViewModel 生命周期只拉一次）。 */
    private var likeSeedStarted = false

    init {
        // 跟随引擎状态投影（iOS 直接读 `player.isFMMode` / `player.currentTrack`）。
        scope.launch {
            controller.state.collect { state ->
                _uiState.update {
                    it.copy(
                        isLoggedIn = isLoggedIn(),
                        isFmMode = state.isFMMode,
                        currentTrack = state.currentTrack.takeIf { track -> state.isFMMode },
                        isPlaying = state.isPlaying,
                        isBuffering = state.isBuffering,
                        error = state.error,
                    )
                }
                // 有漫游曲时才需要红心真值（无曲时心形不展示）。
                if (state.isFMMode && state.currentTrack != null) seedLikedOnce()
            }
        }
    }

    /** 开始漫游（iOS `player.startFM()`）。 */
    fun start() {
        controller.startFM()
    }

    /** 播放 / 暂停切换。 */
    fun togglePlay() {
        controller.togglePlay()
    }

    /** 下一首（iOS `player.fmNext()`）。 */
    fun next() {
        controller.fmNext()
    }

    /** 不喜欢，丢弃当前（iOS `player.fmTrash()`）。 */
    fun trash() {
        controller.fmTrash()
    }

    /**
     * 收藏 / 取消收藏当前曲（乐观更新，失败回滚并发出 [likeFailures]）。
     *
     * 取消收藏复用同一接口：`NeteaseApi.likeTrack(id, like)` 的 `like` 参数即
     * 「收藏 / 取消」双向语义（官方 `v1/radio/like`），无需另造接口。
     */
    fun toggleLike(trackID: Long) {
        val wasLiked = _uiState.value.likedTrackIDs.contains(trackID)
        val target = !wasLiked
        applyLiked(trackID, target)

        val client = api
        if (client == null) {
            // 无网络能力时不做假成功：回滚并提示失败。
            applyLiked(trackID, wasLiked)
            _likeFailures.tryEmit(Unit)
            return
        }
        scope.launch {
            runCatching { client.likeTrack(trackID, target) }
                .onFailure {
                    applyLiked(trackID, wasLiked)
                    _likeFailures.tryEmit(Unit)
                }
        }
    }

    /** 取消内部协程（页面销毁时由 UI 调用，避免状态收集器常驻泄漏）。 */
    fun cancel() {
        scope.cancel()
    }

    /** 写入 / 移除本地红心集合中的某一首。 */
    private fun applyLiked(trackID: Long, liked: Boolean) {
        _uiState.update { state ->
            val next = if (liked) {
                state.likedTrackIDs + trackID
            } else {
                state.likedTrackIDs - trackID
            }
            state.copy(likedTrackIDs = next)
        }
    }

    /**
     * 种子化红心集合（每个 VM 生命周期一次）。
     *
     * 失败静默保留本地集合：此时心形按「未收藏」渲染，用户点击仍可乐观切换并
     * 尝试写服务端（下一次 [toggleLike] 会给出失败提示）。
     */
    private fun seedLikedOnce() {
        if (likeSeedStarted) return
        if (!isLoggedIn()) return
        val client = api ?: return
        likeSeedStarted = true
        scope.launch {
            val uid = runCatching { client.userAccount()?.userId }.getOrNull() ?: return@launch
            runCatching { client.likedTrackIDs(uid) }
                .onSuccess { ids ->
                    // 合并而非覆盖：种子到达前用户可能已乐观切换过若干首。
                    _uiState.update { it.copy(likedTrackIDs = it.likedTrackIDs + ids) }
                }
        }
    }
}
