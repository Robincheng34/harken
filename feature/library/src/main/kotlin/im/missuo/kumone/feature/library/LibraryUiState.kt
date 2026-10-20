// Ported from kumone by missuo (LGPL-3.0)
//
// 我的页 UI 状态（T24 `LibraryUiState.kt`）。
//
// 登录门控同 iOS `SidebarView`：未登录仅展示登录引导；登录后展示官方新版「我的」
// 页：红底个人区（头像 / 昵称 / 等级 / VIP / 关注粉丝听歌）+ 快捷入口 + 音乐页签
// （近期 / 创建 / 收藏 / 专辑）。

package im.missuo.kumone.feature.library

import im.missuo.kumone.net.model.AlbumSummary
import im.missuo.kumone.net.model.PlaylistSummary
import im.missuo.kumone.net.model.Track

/** 我的页 UI 状态。 */
data class LibraryUiState(
    val isLoggedIn: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    /** 用户昵称（登录后展示）。 */
    val nickname: String = "",
    val avatarUrl: String? = null,
    /** 个人主页背景图（登录后展示，可被主图挂件覆盖）。 */
    val backgroundUrl: String? = null,
    /** VIP 类型（>0 显示 VIP 徽标）。 */
    val vipType: Int = 0,
    /** 个性签名（空则不占位）。 */
    val signature: String = "",
    /** 等级；服务端不回为 null（不填假数据）。 */
    val level: Int? = null,
    /** 关注数；服务端不回为 null。 */
    val follows: Int? = null,
    /** 粉丝数；服务端不回为 null。 */
    val followeds: Int? = null,
    /** 累计听歌数（首）；服务端不回为 null。 */
    val listenSongs: Int? = null,
    /** 「我喜欢的音乐」歌单（`specialType == 5`），可能为空。 */
    val likedSongs: PlaylistSummary? = null,
    /** 收藏的专辑。 */
    val likedAlbums: List<AlbumSummary> = emptyList(),
    /** 最近播放曲目（`playRecords(week = false)` 前 20 首）。 */
    val recentTracks: List<Track> = emptyList(),
    /** 我创建的歌单（`specialType != 5`）。 */
    val createdPlaylists: List<PlaylistSummary> = emptyList(),
    /** 我收藏的歌单（`subscribed`）。 */
    val subscribedPlaylists: List<PlaylistSummary> = emptyList(),
) {
    /** iOS: 未登录分支只展示登录引导。 */
    val showPersonalRows: Boolean get() = isLoggedIn
}
