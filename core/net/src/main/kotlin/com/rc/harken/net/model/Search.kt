// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken.net.model

import kotlinx.serialization.Serializable

/** Cloud-search categories (iOS `NeteaseAPI.SearchType`). */
enum class SearchType(val value: Int) {
    SONGS(1),
    ALBUMS(10),
    ARTISTS(100),
    PLAYLISTS(1000),
    ;

    companion object {
        fun fromValue(value: Int): SearchType? = entries.firstOrNull { it.value == value }
    }
}

/** One `/cloudsearch/pc` result section (empty lists when the type has no results). */
@Serializable
data class SearchResult(
    val songs: List<Track> = emptyList(),
    val albums: List<AlbumSummary> = emptyList(),
    val artists: List<ArtistSummary> = emptyList(),
    val playlists: List<PlaylistSummary> = emptyList(),
    @Serializable(with = LenientNullableIntSerializer::class)
    val songCount: Int? = null,
    @Serializable(with = LenientNullableIntSerializer::class)
    val albumCount: Int? = null,
    @Serializable(with = LenientNullableIntSerializer::class)
    val artistCount: Int? = null,
    @Serializable(with = LenientNullableIntSerializer::class)
    val playlistCount: Int? = null,
)

/** `/cloudsearch/pc` envelope. */
@Serializable
data class SearchResponse(
    val result: SearchResult? = null,
)

/** `/search/suggest/web` result body. */
@Serializable
data class SearchSuggestBody(
    val songs: List<Track>? = null,
    val artists: List<ArtistSummary>? = null,
    val albums: List<AlbumSummary>? = null,
    val playlists: List<PlaylistSummary>? = null,
    /** 关键词补全（官方「猜你想搜」行）。 */
    val allMatch: List<SearchSuggestKeyword>? = null,
)

/** 联想关键词（`allMatch` 元素）。 */
@Serializable
data class SearchSuggestKeyword(
    val keyword: String,
    @Serializable(with = LenientNullableIntSerializer::class)
    val type: Int? = null,
)

/** `/search/suggest/web` envelope. */
@Serializable
data class SearchSuggestResponse(
    val result: SearchSuggestBody? = null,
)

/**
 * `/search/hot/detail` 热搜榜条目（官方热搜榜行）。
 *
 * @param iconType 官方角标类型：1=热 2=新 3=沸 4=爆（0/其他=无角标）。
 * @param iconUrl 官方定制角标图片（优先于 [iconType] 文字角标）。
 * @param alg 官方推荐位来源标记：普通榜单条目为空或 `alg_search_rec_*`，
 *   推广位条目为 `featured`（官方热搜榜第 3 条那类广告，UI 侧过滤不展示）。
 */
@Serializable
data class HotSearchItem(
    val searchWord: String,
    @Serializable(with = LenientNullableIntSerializer::class)
    val score: Int? = null,
    @Serializable(with = LenientNullableIntSerializer::class)
    val iconType: Int? = null,
    val iconUrl: String? = null,
    val alg: String? = null,
)

/** `/search/hot/detail` envelope。 */
@Serializable
data class HotSearchDetailResponse(
    val data: List<HotSearchItem> = emptyList(),
    val code: Int = 200,
)
