// Ported from kumone by missuo (LGPL-3.0)
//
// 漫游 ViewModel（T23 `FmViewModel.kt`）。
//
// 漫游播放由 `PlaybackCommands` / `MusicController` 承载（引擎内 FM 取数与「不喜欢」
// 丢弃），本 ViewModel 只负责把引擎状态投影为 [FmUiState] 并转发四个动作。
//
// 为可纯 JVM 单测，依赖窄接口 [FmPlaybackPort] 而非 final 的 `MusicController`；
// `MusicController` 结构上已满足该接口（`state` + 四个命令方法），由 `:app` 直接注入。

package im.missuo.kumone.feature.fm

import im.missuo.kumone.player.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
) {
    private val _uiState: MutableStateFlow<FmUiState> = MutableStateFlow(FmUiState())
    val uiState: StateFlow<FmUiState> = _uiState.asStateFlow()

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

    /** 取消内部协程（页面销毁时由 UI 调用，避免状态收集器常驻泄漏）。 */
    fun cancel() {
        scope.cancel()
    }
}
