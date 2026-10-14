// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.net.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A cloud-disk entry. The real payload nests metadata under `privateCloud`
 * and the playable track under `simpleSong`; older docs show flat fields,
 * so both shapes are tolerated (resolution order mirrors iOS).
 */
@Serializable
data class CloudSongItem(
    @SerialName("songId")
    @Serializable(with = LenientNullableLongSerializer::class)
    private val flatSongId: Long? = null,
    @SerialName("songName")
    private val flatSongName: String? = null,
    @SerialName("artist")
    private val flatArtist: String? = null,
    @SerialName("fileSize")
    @Serializable(with = LenientNullableLongSerializer::class)
    private val flatFileSize: Long? = null,
    val simpleSong: Track? = null,
    @SerialName("privateCloud")
    private val privateCloud: PrivateCloud? = null,
) {
    @Serializable
    data class PrivateCloud(
        @Serializable(with = LenientNullableLongSerializer::class)
        val songId: Long? = null,
        val song: String? = null,
        val artist: String? = null,
        @Serializable(with = LenientNullableLongSerializer::class)
        val fileSize: Long? = null,
    )

    val songId: Long
        get() = flatSongId ?: privateCloud?.songId ?: simpleSong?.id ?: 0L

    val songName: String?
        get() = flatSongName ?: privateCloud?.song ?: simpleSong?.name

    val artist: String?
        get() = flatArtist ?: privateCloud?.artist

    val fileSize: Long
        get() = flatFileSize ?: privateCloud?.fileSize ?: 0L

    val id: Long get() = songId
}

/** `/v1/cloud/get` envelope. */
@Serializable
data class CloudResponse(
    val data: List<CloudSongItem> = emptyList(),
    val hasMore: Boolean? = null,
    /** Bytes; served as a number or a numeric string depending on account age. */
    @Serializable(with = LenientNullableLongSerializer::class)
    val size: Long? = null,
    @Serializable(with = LenientNullableLongSerializer::class)
    val maxSize: Long? = null,
)
