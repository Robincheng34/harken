// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken.playback

/**
 * Playback quality tiers and their `songURL` API level strings
 * (iOS `AudioQuality`; the ladder ascends STANDARD → HIRES).
 *
 * @param displayName 中文名称（设置页显示）。
 * @param params 音质参数摘要（比特率 / 采样率 / 编码，设置页展示）。
 * @param requiresLogin 是否需要登录（且为会员）才能由官方端点取流。
 *     false = 免登录可用：由「灰色解锁」第三方音源提供（pyncmd/酷我/酷狗最高 320kbps，即「极高」）。
 *     true  = 必须登录且为黑胶VIP：官方无损 / Hi-Res 母带，第三方音源无法提供。
 */
enum class AudioQuality(
    val apiLevel: String,
    val displayName: String,
    val params: String,
    val requiresLogin: Boolean,
) {
    STANDARD("standard", "标准", "128kbps / MP3", false),
    HIGHER("higher", "较高", "192kbps / MP3", false),
    EXHIGH("exhigh", "极高", "320kbps / MP3", false),
    LOSSLESS("lossless", "无损", "16bit / 44.1kHz / FLAC", true),
    HIRES("hires", "Hi-Res", "24bit / ≥96kHz / FLAC", true),
    ;
}
