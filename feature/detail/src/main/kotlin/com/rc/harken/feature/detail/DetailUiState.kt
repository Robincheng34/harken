// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 详情页 UI 状态（T21 `DetailUiState.kt`）。
//
// 三类详情共用一个状态容器（歌单 / 专辑 / 歌手），由 [DetailKind] 区分头部布局与
// 数据来源，对应 iOS `PlaylistDetailView` / `AlbumDetailView` / `ArtistDetailView`。

package com.rc.harken.feature.detail

import com.rc.harken.net.model.AlbumSummary
import com.rc.harken.net.model.ArtistSummary
import com.rc.harken.net.model.Track

/** 详情页种类。 */
enum class DetailKind { PLAYLIST, ALBUM, ARTIST }

/** 详情页头部（歌单/专辑/歌手共用的字段集合）。 */
data class DetailHeaderState(
    val id: Long = 0,
    val title: String = "",
    val subtitle: String = "",
    val coverURL: String? = null,
    val description: String? = null,
    val creatorName: String = "",
    val creatorAvatar: String? = null,
    val playCount: Int = 0,
    val trackCount: Int = 0,
    val subscribed: Boolean = false,
    val subscribedCount: Int = 0,
) {
    /**
     * 派生一份「收藏态 = [subscribed]」的头部。
     *
     * @param adjustCount 是否同时调整收藏数（仅歌单接口回传 `subscribedCount`）。
     */
    fun withSubscription(subscribed: Boolean, adjustCount: Boolean): DetailHeaderState = copy(
        subscribed = subscribed,
        subscribedCount = if (adjustCount) {
            (subscribedCount + if (subscribed) 1 else -1).coerceAtLeast(0)
        } else {
            subscribedCount
        },
    )
}

/** 详情页单一 UI 状态。 */
data class DetailUiState(
    val kind: DetailKind = DetailKind.PLAYLIST,
    val isLoading: Boolean = true,
    val error: String? = null,
    val header: DetailHeaderState = DetailHeaderState(),
    val tracks: List<Track> = emptyList(),
    /** 歌手页的「热门专辑」横滑（iOS `ArtistDetailView`）。 */
    val artistAlbums: List<AlbumSummary> = emptyList(),
    val artist: ArtistSummary? = null,
    /** 曲目是否已完整加载（歌单 trackIds → songDetails 补齐完成后为 true）。 */
    val tracksComplete: Boolean = false,
    val totalTrackCount: Int = 0,
    /** 收藏请求进行中（防连点造成乐观状态互相覆盖）。 */
    val isSubscribing: Boolean = false,
) {
    val isEmpty: Boolean get() = header.id == 0L && !isLoading && error == null
}
