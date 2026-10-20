// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.net.model

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/** `{ "name": ... }` artist stub embedded in [AlbumSummary]. */
@Serializable
data class AlbumArtistName(
    val name: String? = null,
)

/** An album as it appears in grids and shelves. */
@Serializable
data class AlbumSummary(
    @Serializable(with = LenientLongSerializer::class)
    val id: Long,
    val name: String = "",
    @JsonNames("picUrl", "cover")
    val picUrl: String? = null,
    val blurPicUrl: String? = null,
    @JsonNames("coverImgUrl", "coverImg")
    val coverImgUrl: String? = null,
    val artist: AlbumArtistName? = null,
    val artists: List<AlbumArtistName>? = null,
    @Serializable(with = LenientLongSerializer::class)
    val publishTime: Long = 0,
    @Serializable(with = LenientIntSerializer::class)
    val size: Int = 0,
    val subType: String? = null,
    @JsonNames("alia", "alias")
    val alias: List<String> = emptyList(),
) {
    /** `artist.name` when present, else `artists` joined by " / " (iOS order). */
    val artistName: String
        get() = when {
            artist?.name != null -> artist.name
            artists != null -> artists.mapNotNull { it.name }.joinToString(" / ")
            else -> ""
        }

    /** 封面优先 `picUrl`，回退 `blurPicUrl` / `coverImgUrl`（部分接口只回后两者）。 */
    val coverUrl: String?
        get() = picUrl ?: blurPicUrl ?: coverImgUrl

    val publishYear: String
        get() {
            if (publishTime <= 0) return ""
            return Instant.ofEpochMilli(publishTime)
                .atZone(ZoneId.systemDefault())
                .year
                .toString()
        }
}

/** `/album/new` envelope. */
@Serializable
data class NewAlbumsResponse(
    val albums: List<AlbumSummary> = emptyList(),
)

/** `/album/detail/dynamic` (eapi) envelope. */
@Serializable
data class AlbumDynamicResponse(
    val isSub: Boolean? = null,
    @Serializable(with = LenientNullableIntSerializer::class)
    val subCount: Int? = null,
)

/** Full album detail. */
@Serializable
data class AlbumDetail(
    @Serializable(with = LenientLongSerializer::class)
    val id: Long,
    val name: String = "",
    val picUrl: String? = null,
    val artist: ArtistSummary? = null,
    @Serializable(with = LenientLongSerializer::class)
    val publishTime: Long = 0,
    val description: String? = null,
    val company: String? = null,
    @Serializable(with = LenientIntSerializer::class)
    val size: Int = 0,
    val subType: String? = null,
)

/** `/v1/album/{id}` envelope. */
@Serializable
data class AlbumDetailResponse(
    val album: AlbumDetail,
    val songs: List<Track> = emptyList(),
)
