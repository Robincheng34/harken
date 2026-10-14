// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.net.model

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
)

/** `/search/suggest/web` envelope. */
@Serializable
data class SearchSuggestResponse(
    val result: SearchSuggestBody? = null,
)
