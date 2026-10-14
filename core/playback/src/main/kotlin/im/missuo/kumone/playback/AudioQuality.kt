// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.playback

/**
 * Playback quality tiers and their `songURL` API level strings
 * (iOS `AudioQuality`; the ladder ascends STANDARD → HIRES).
 */
enum class AudioQuality(val apiLevel: String) {
    STANDARD("standard"),
    HIGHER("higher"),
    EXHIGH("exhigh"),
    LOSSLESS("lossless"),
    HIRES("hires"),
    ;
}
