// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.playback

/**
 * Playback quality tiers and their `songURL` API level strings
 * (iOS `AudioQuality`; the ladder ascends STANDARD → HIRES).
 *
 * @param displayName 中文名称（设置页显示）。
 * @param params 音质参数摘要（比特率 / 采样率 / 编码，设置页展示）。
 */
enum class AudioQuality(
    val apiLevel: String,
    val displayName: String,
    val params: String,
) {
    STANDARD("standard", "标准", "128kbps / MP3"),
    HIGHER("higher", "较高", "192kbps / MP3"),
    EXHIGH("exhigh", "极高", "320kbps / MP3"),
    LOSSLESS("lossless", "无损", "16bit / 44.1kHz / FLAC"),
    HIRES("hires", "Hi-Res", "24bit / ≥96kHz / FLAC"),
    ;
}
