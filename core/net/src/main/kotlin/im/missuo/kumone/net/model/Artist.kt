// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.net.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/** An artist as it appears in grids, shelves and search results. */
@Serializable
data class ArtistSummary(
    @Serializable(with = LenientLongSerializer::class)
    val id: Long,
    val name: String = "",
    @JsonNames("picUrl", "cover", "avatar", "img1v1Url")
    val picUrl: String? = null,
    @Serializable(with = LenientIntSerializer::class)
    val albumSize: Int = 0,
    @Serializable(with = LenientIntSerializer::class)
    val musicSize: Int = 0,
    val briefDesc: String? = null,
    val alias: List<String> = emptyList(),
    val followed: Boolean = false,
)

/** `/v1/artist/{id}` envelope. */
@Serializable
data class ArtistResponse(
    val artist: ArtistSummary,
    val hotSongs: List<Track> = emptyList(),
)

/** `/artist/albums/{id}` envelope. */
@Serializable
data class ArtistAlbumsResponse(
    val hotAlbums: List<AlbumSummary> = emptyList(),
    val more: Boolean? = null,
)

/** `/toplist/artist` envelope. */
@Serializable
data class ToplistArtistResponse(
    val list: ToplistArtistBody,
) {
    @Serializable
    data class ToplistArtistBody(
        val artists: List<ArtistSummary> = emptyList(),
    )
}
