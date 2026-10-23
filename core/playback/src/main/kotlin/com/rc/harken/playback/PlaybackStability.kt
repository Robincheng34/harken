// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken.playback

/**
 * 播放稳定性判定（引擎熔断 [PlaybackEngine.MAX_CONSECUTIVE_FAILURES] 的判据）。
 *
 * 背景：原判据是绝对阈值「播放位置 ≥ 5 秒」。但专辑/歌单里真实存在
 * < 5 秒的曲目（intro、skit、过场曲），它们自然播完（STATE_ENDED）时
 * 播放位置 ≈ 时长 < 5 秒，会被误判为「秒挂的坏源」——连续 5 首短曲后
 * 队列直接停摆（用户视角：莫名停了）。
 *
 * 判定规则：
 *  - 播放位置 ≥ [stableThresholdMs] → 稳定（长曲听足了）；
 *  - 或已播到曲目尾部（≥ 时长 - 尾部窗口）→ 稳定。自然播完时 ExoPlayer 的
 *    position 精确等于 duration，窗口只需覆盖最后一帧；窗口取
 *    min(250ms, 时长/4) 对极短曲按比例收窄，「每次播一半就挂」的坏源
 *    过不了窗口，熔断依然生效；
 *  - 时长为 0/未知（流媒体 prepare 前）→ 退化为绝对阈值，宁严勿松。
 *
 * 注意：公开而非 internal——调用方在 core/player（另一个 Gradle 模块）。
 */
fun isStablePlayback(
    positionMs: Long,
    durationMs: Long,
    stableThresholdMs: Long,
): Boolean {
    if (positionMs >= stableThresholdMs) return true
    if (durationMs > 0) {
        val tailWindow = minOf(250L, durationMs / 4)
        if (positionMs >= durationMs - tailWindow) return true
    }
    return false
}
