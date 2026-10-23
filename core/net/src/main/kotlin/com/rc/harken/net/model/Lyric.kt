// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken.net.model

import kotlinx.serialization.Serializable

/** One lyric body (`lrc` / `tlyric` / `yrc` / …). */
@Serializable
data class LyricBody(
    val lyric: String? = null,
)

/** Contributor credit attached to a lyric or its translation. */
@Serializable
data class LyricContributor(
    val nickname: String? = null,
)

/**
 * `/song/lyric/v1` (preferred, also carries verbatim `yrc`) or the classic
 * `/song/lyric` fallback response.
 */
@Serializable
data class LyricResponse(
    val lrc: LyricBody? = null,
    val tlyric: LyricBody? = null,
    val romalrc: LyricBody? = null,
    /** Verbatim (word-by-word) lyrics for karaoke highlighting. */
    val yrc: LyricBody? = null,
    val ytlrc: LyricBody? = null,
    val yromalrc: LyricBody? = null,
    val lyricUser: LyricContributor? = null,
    val transUser: LyricContributor? = null,
    val nolyric: Boolean? = null,
    val uncollected: Boolean? = null,
)
