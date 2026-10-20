// Ported from kumone by missuo (LGPL-3.0)
//
// 播放页 ViewModel（T27 `NowPlayingViewModel.kt`）。
//
// 承载播放页的 UI 状态：歌词面板开合、队列面板开合、逐行同步所需的播放位置、
// 歌曲信息弹窗与更多菜单。播放状态本身来自 `PlayerState`（含
// `lyrics: ParsedLyrics?`）；本 VM 只管面板与交互态，纯 Kotlin + 协程。
//
// 红心/关注的真相在进程级 [NowPlayingSocialStore]（种子一次、多入口共享），
// 本 VM 只把面板事件与存储指令接通。

package im.missuo.kumone.feature.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 播放页 UI 状态。 */
data class NowPlayingUiState(
    val showLyricsPanel: Boolean = false,
    val showQueuePanel: Boolean = false,
    /** 触摸歌词时暂停自动滚动（iOS 触摸即暂停滚动）。 */
    val isLyricsScrubbing: Boolean = false,
    /** 更多菜单（底部五图标行「更多」）。 */
    val showMoreMenu: Boolean = false,
    /**
     * 全屏沉浸式歌词模式：歌词显示且用户 5 秒无操作后自动进入，点击空白区域退出。
     * 进入后歌词全屏覆盖整个播放页，隐藏常规 chrome（顶栏 / 社交行 / 控制条）。
     */
    val isLyricsImmersive: Boolean = false,
)

class NowPlayingViewModel {
    private val _uiState: MutableStateFlow<NowPlayingUiState> = MutableStateFlow(NowPlayingUiState())
    val uiState: StateFlow<NowPlayingUiState> = _uiState.asStateFlow()

    /** 切换歌词面板（封面 ⇄ 歌词）。 */
    fun toggleLyricsPanel() {
        _uiState.update {
            it.copy(
                showLyricsPanel = !it.showLyricsPanel,
                // 打开歌词时收起队列（iOS 侧滑面板互斥）；切歌词/封面时退出沉浸。
                showQueuePanel = if (!it.showLyricsPanel) false else it.showQueuePanel,
                isLyricsImmersive = false,
            )
        }
    }

    /** 进入全屏沉浸式歌词（5 秒无操作自动触发）。 */
    fun enterLyricsImmersive() {
        if (_uiState.value.isLyricsImmersive) return
        _uiState.update { it.copy(isLyricsImmersive = true) }
    }

    /** 退出全屏沉浸式歌词（点击空白区域 / 返回 / 收起箭头）。 */
    fun exitLyricsImmersive() {
        if (!_uiState.value.isLyricsImmersive) return
        // 退出本身就是一次用户操作：重置无操作计时。否则从「空闲≥5秒自动沉浸」态返回时，
        // 系统返回手势不会经指针屏障触发 markInteraction，计时仍是旧的（≥5秒前），
        // 自动沉浸定时器会在 ~250ms 后立刻重新进入沉浸，控件弹出后又被吞掉、稳不住。
        markInteraction()
        _uiState.update { it.copy(isLyricsImmersive = false) }
    }

    /** 最近一次用户操作时间戳（毫秒），用于「5 秒无操作自动沉浸」。 */
    var lastInteractionMs: Long = System.currentTimeMillis()
        private set

    /** 记录一次用户操作（任何触摸 / 滑动 / 点击都会重置无操作计时）。 */
    fun markInteraction() {
        lastInteractionMs = System.currentTimeMillis()
    }

    /** 切换队列面板。 */
    fun toggleQueuePanel() {
        _uiState.update {
            it.copy(
                showQueuePanel = !it.showQueuePanel,
                showLyricsPanel = if (!it.showQueuePanel) false else it.showLyricsPanel,
                // 队列与歌词互斥（见 toggleLyricsPanel）：打开队列时一并退出沉浸，
                // 否则关掉队列后会残留 isLyricsImmersive=true，导致播放页卡在沉浸态
                // 且不再接受「5 秒无操作自动沉浸」的计时。
                // 注意判断用的是「打开前」的旧值：!it.showQueuePanel 成立即本次是在
                // 打开队列，必须退出沉浸；关闭队列时保留现状（此时已是 false）。
                isLyricsImmersive = if (!it.showQueuePanel) false else it.isLyricsImmersive,
            )
        }
    }

    /** 歌词触摸开始 / 结束（暂停或恢复自动滚动）。 */
    fun setLyricsScrubbing(scrubbing: Boolean) {
        _uiState.update { it.copy(isLyricsScrubbing = scrubbing) }
    }

    /** 更多菜单开合。 */
    fun toggleMoreMenu() {
        _uiState.update { it.copy(showMoreMenu = !it.showMoreMenu) }
    }
}
