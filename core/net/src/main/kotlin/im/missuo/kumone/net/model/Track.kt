// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.net.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonNull

/** Artist reference embedded in a [Track]. */
@Serializable
data class ArtistRef(
    @Serializable(with = LenientLongSerializer::class)
    val id: Long = 0,
    val name: String = "",
)

/** Album reference embedded in a [Track]. */
@Serializable
data class AlbumRef(
    @Serializable(with = LenientLongSerializer::class)
    val id: Long = 0,
    val name: String = "",
    val picUrl: String? = null,
)

/**
 * A unified track model that decodes both the "v3" song shape (`ar`/`al`/`dt`)
 * and the legacy shape (`artists`/`album`/`duration`).
 */
@Serializable
data class Track(
    @Serializable(with = LenientLongSerializer::class)
    val id: Long,
    val name: String = "",
    @JsonNames("ar", "artists")
    val artists: List<ArtistRef> = emptyList(),
    @JsonNames("al", "album")
    val album: AlbumRef = AlbumRef(),
    @JsonNames("dt", "duration")
    @Serializable(with = LenientLongSerializer::class)
    val durationMS: Long = 0,
    @JsonNames("alia", "alias")
    val alias: List<String> = emptyList(),
    @JsonNames("tns")
    val transNames: List<String> = emptyList(),
    @Serializable(with = LenientIntSerializer::class)
    val fee: Int = 0,
    @JsonNames("mv")
    @Serializable(with = LenientIntSerializer::class)
    val mvID: Int = 0,
    @JsonNames("no")
    @Serializable(with = LenientIntSerializer::class)
    val trackNo: Int = 0,
    val cd: String? = null,
    @SerialName("privilege")
    val embeddedPrivilege: TrackPrivilege? = null,
    /** Present and non-null ⇔ the track is flagged no-copyright (`noCopyrightRcmd`). */
    @SerialName("noCopyrightRcmd")
    private val noCopyrightRcmd: JsonElement? = null,
    /** Present and non-null ⇔ the track lives on the user's cloud disk (`pc`). */
    @SerialName("pc")
    private val pc: JsonElement? = null,
) {
    /** `noCopyrightRcmd` exists and is not JSON null (iOS: `c.contains && !decodeNil`). */
    val noCopyright: Boolean get() = noCopyrightRcmd != null && noCopyrightRcmd !is JsonNull

    /** `pc` exists and is not JSON null. */
    val isCloud: Boolean get() = pc != null && pc !is JsonNull

    val artistNames: String get() = artists.joinToString(" / ") { it.name }

    val durationSeconds: Double get() = durationMS / 1000.0

    val subtitle: String? get() = transNames.firstOrNull() ?: alias.firstOrNull()

    /**
     * Mirrors YesPlayMusic's `isTrackPlayable` decision chain, with the VIP
     * check widened to cover 黑胶 SVIP (vipType 110 etc).
     */
    fun playability(
        privilege: TrackPrivilege?,
        isLoggedIn: Boolean,
        vipType: Int,
    ): TrackPlayability {
        val effective = privilege ?: embeddedPrivilege
        if ((effective?.pl ?: 0) > 0) return TrackPlayability.PLAYABLE
        if (isLoggedIn && effective?.cs == true) return TrackPlayability.PLAYABLE
        val effectiveFee = effective?.fee ?: fee
        if (effectiveFee == 1) {
            return if (vipType > 0) TrackPlayability.PLAYABLE else TrackPlayability.VIP_ONLY
        }
        if (effectiveFee == 4) return TrackPlayability.PAID_ALBUM
        if (noCopyright) return TrackPlayability.NO_COPYRIGHT
        if ((effective?.st ?: 0) < 0 && isLoggedIn) return TrackPlayability.DELISTED
        return TrackPlayability.PLAYABLE
    }
}

/** Playability flags per track, returned in parallel `privileges` arrays. */
@Serializable
data class TrackPrivilege(
    @Serializable(with = LenientLongSerializer::class)
    val id: Long,
    @Serializable(with = LenientNullableIntSerializer::class)
    val fee: Int? = null,
    @Serializable(with = LenientNullableIntSerializer::class)
    val pl: Int? = null,
    @Serializable(with = LenientNullableIntSerializer::class)
    val st: Int? = null,
    val cs: Boolean? = null,
    @Serializable(with = LenientNullableIntSerializer::class)
    val maxbr: Int? = null,
)

/** Coarse playability verdict with a user-facing reason. */
enum class TrackPlayability(val reason: String?) {
    PLAYABLE(null),
    VIP_ONLY("VIP 专属"),
    PAID_ALBUM("付费专辑"),
    NO_COPYRIGHT("无版权"),
    DELISTED("已下架"),
}

/** `/v3/song/detail` response: songs plus their parallel privileges array. */
@Serializable
data class SongDetailResponse(
    val songs: List<Track> = emptyList(),
    val privileges: List<TrackPrivilege> = emptyList(),
)
