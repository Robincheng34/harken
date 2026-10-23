// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken.net.model

import kotlinx.serialization.Serializable

/** One entry of `/song/enhance/player/url/v1`. */
@Serializable
data class SongURLData(
    @Serializable(with = LenientLongSerializer::class)
    val id: Long,
    val url: String? = null,
    @Serializable(with = LenientIntSerializer::class)
    val br: Int = 0,
    @Serializable(with = LenientLongSerializer::class)
    val size: Long = 0,
    val type: String? = null,
    val level: String? = null,
    @Serializable(with = LenientIntSerializer::class)
    val fee: Int = 0,
    val freeTrialInfo: FreeTrialInfo? = null,
    @Serializable(with = LenientLongSerializer::class)
    val time: Long = 0,
    @Serializable(with = LenientNullableIntSerializer::class)
    val code: Int? = null,
    val md5: String? = null,
)

/** Trial-fragment window (milliseconds) when the full track is not free. */
@Serializable
data class FreeTrialInfo(
    @Serializable(with = LenientNullableLongSerializer::class)
    val start: Long? = null,
    @Serializable(with = LenientNullableLongSerializer::class)
    val end: Long? = null,
)

/** `/song/enhance/player/url/v1` envelope. */
@Serializable
data class SongURLResponse(
    val data: List<SongURLData> = emptyList(),
)
