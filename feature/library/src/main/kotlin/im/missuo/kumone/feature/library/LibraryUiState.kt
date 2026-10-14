// Ported from kumone by missuo (LGPL-3.0)
//
// 我的页 UI 状态（T24 `LibraryUiState.kt`）。
//
// 登录门控同 iOS `SidebarView`：未登录仅展示「已下载」；登录后展示我喜欢的音乐 /
// 每日推荐 / 最近播放 / 我的收藏 / 音乐云盘 + 创建的歌单 + 收藏的歌单。

package im.missuo.kumone.feature.library

import im.missuo.kumone.net.model.PlaylistSummary

/** 我的页 UI 状态。 */
data class LibraryUiState(
    val isLoggedIn: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    /** 用户昵称（登录后展示）。 */
    val nickname: String = "",
    val avatarUrl: String? = null,
    /** 我创建的歌单（`specialType != 5`）。 */
    val createdPlaylists: List<PlaylistSummary> = emptyList(),
    /** 我收藏的歌单（`subscribed`）。 */
    val subscribedPlaylists: List<PlaylistSummary> = emptyList(),
) {
    /** iOS: 未登录分支只展示「已下载」。 */
    val showPersonalRows: Boolean get() = isLoggedIn
}
