// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 漫游页 UI 状态（T23 `FmUiState.kt`）。
//
// 漫游态由播放引擎持有（`PlayerState.isFMMode` / `currentTrack`），本状态仅承载
// 登录门控、错误提示与红心收藏集合，对应 iOS `FMView` 的 `account.hasAuthCookie` 分支。

package com.rc.harken.feature.fm

import com.rc.harken.net.model.Track

/** 漫游页 UI 状态。 */
data class FmUiState(
    val isLoggedIn: Boolean = false,
    val isFmMode: Boolean = false,
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val error: String? = null,
    /**
     * 已收藏（红心）的歌曲 id 集合。
     *
     * 真源优先取自服务端 `song/like/get`（登录后种子化一次），此后由
     * [FmViewModel.toggleLike] 乐观维护；`feature/fm` 不依赖 `feature/player`，
     * 故不复用 `NowPlayingSocialStore`（后者仅 player 模块内可见）。
     */
    val likedTrackIDs: Set<Long> = emptySet(),
) {
    /** iOS: 未登录时展示登录引导而非内容。 */
    val showLoginPrompt: Boolean get() = !isLoggedIn

    /** 当前漫游曲是否已收藏（决定控制条心形实心/空心）。 */
    val isCurrentTrackLiked: Boolean
        get() = currentTrack?.let { likedTrackIDs.contains(it.id) } == true
}
