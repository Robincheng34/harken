// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.net.model

import kotlinx.serialization.Serializable

/** One entry of the weekly/all-time play record. */
@Serializable
data class PlayRecordItem(
    @Serializable(with = LenientIntSerializer::class)
    val playCount: Int = 0,
    @Serializable(with = LenientIntSerializer::class)
    val score: Int = 0,
    val song: Track,
)

/** `/v1/play/record` envelope. */
@Serializable
data class PlayRecordResponse(
    val weekData: List<PlayRecordItem>? = null,
    val allData: List<PlayRecordItem>? = null,
)
