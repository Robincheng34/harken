// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.net.model

import kotlinx.serialization.Serializable

/** One ranked list (`/toplist`, eapi). */
@Serializable
data class ToplistItem(
    @Serializable(with = LenientLongSerializer::class)
    val id: Long,
    val name: String = "",
    val coverImgUrl: String? = null,
    val updateFrequency: String? = null,
    val tracks: List<ToplistTrackPreview> = emptyList(),
    @Serializable(with = LenientIntSerializer::class)
    val playCount: Int = 0,
)

/** "first – second" preview line of a [ToplistItem]. */
@Serializable
data class ToplistTrackPreview(
    val first: String = "",
    val second: String = "",
)

/** `/toplist` (eapi) envelope. */
@Serializable
data class ToplistResponse(
    val list: List<ToplistItem> = emptyList(),
)
