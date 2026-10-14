// Ported from kumone by missuo (LGPL-3.0)
//
// 漫游页 UI 状态（T23 `FmUiState.kt`）。
//
// 漫游态由播放引擎持有（`PlayerState.isFMMode` / `currentTrack`），本状态仅承载
// 登录门控与错误提示，对应 iOS `FMView` 的 `account.hasAuthCookie` 分支。

package im.missuo.kumone.feature.fm

import im.missuo.kumone.net.model.Track

/** 漫游页 UI 状态。 */
data class FmUiState(
    val isLoggedIn: Boolean = false,
    val isFmMode: Boolean = false,
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val error: String? = null,
) {
    /** iOS: 未登录时展示登录引导而非内容。 */
    val showLoginPrompt: Boolean get() = !isLoggedIn
}
