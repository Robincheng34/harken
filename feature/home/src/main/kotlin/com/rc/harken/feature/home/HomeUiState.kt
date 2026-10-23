// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 首页 UI 状态（T18 `HomeUiState.kt`，iOS `HomeViewModel` 状态 1:1 移植）。

package com.rc.harken.feature.home

import com.rc.harken.net.model.AlbumSummary
import com.rc.harken.net.model.ArtistSummary
import com.rc.harken.net.model.PlaylistSummary
import com.rc.harken.net.model.ToplistItem
import com.rc.harken.net.model.Track

/** 雷达歌单卡片（iOS `HomeViewModel.RadarPlaylist`）。 */
data class RadarPlaylist(
    val id: Long,
    val title: String,
    val subtitle: String?,
    val coverURL: String?,
)

/** 首页聚合状态。 */
data class HomeUiState(
    val isLoading: Boolean = true,
    /** 下拉刷新中（保留内容，后台重拉；区别于 [isLoading] 的「全量骨架」）。 */
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val recommendPlaylists: List<PlaylistSummary> = emptyList(),
    val radarPlaylists: List<RadarPlaylist> = emptyList(),
    val dailySongs: List<Track> = emptyList(),
    /** 每日推荐首曲封面（功能卡背景）。 */
    val dailyFirstCover: String? = null,
    val toplists: List<ToplistItem> = emptyList(),
    val newAlbums: List<AlbumSummary> = emptyList(),
    val artists: List<ArtistSummary> = emptyList(),
)
