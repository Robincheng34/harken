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
    /** 歌曲信息弹窗（底部五图标行「歌曲信息」）。 */
    val showSongInfo: Boolean = false,
    /** 更多菜单（底部五图标行「更多」）。 */
    val showMoreMenu: Boolean = false,
)

class NowPlayingViewModel {
    private val _uiState: MutableStateFlow<NowPlayingUiState> = MutableStateFlow(NowPlayingUiState())
    val uiState: StateFlow<NowPlayingUiState> = _uiState.asStateFlow()

    /** 切换歌词面板（封面 ⇄ 歌词）。 */
    fun toggleLyricsPanel() {
        _uiState.update {
            it.copy(
                showLyricsPanel = !it.showLyricsPanel,
                // 打开歌词时收起队列（iOS 侧滑面板互斥）。
                showQueuePanel = if (!it.showLyricsPanel) false else it.showQueuePanel,
            )
        }
    }

    /** 切换队列面板。 */
    fun toggleQueuePanel() {
        _uiState.update {
            it.copy(
                showQueuePanel = !it.showQueuePanel,
                showLyricsPanel = if (!it.showQueuePanel) false else it.showLyricsPanel,
            )
        }
    }

    /** 歌词触摸开始 / 结束（暂停或恢复自动滚动）。 */
    fun setLyricsScrubbing(scrubbing: Boolean) {
        _uiState.update { it.copy(isLyricsScrubbing = scrubbing) }
    }

    /** 歌曲信息弹窗开合。 */
    fun toggleSongInfo() {
        _uiState.update { it.copy(showSongInfo = !it.showSongInfo) }
    }

    /** 更多菜单开合。 */
    fun toggleMoreMenu() {
        _uiState.update { it.copy(showMoreMenu = !it.showMoreMenu) }
    }
}
