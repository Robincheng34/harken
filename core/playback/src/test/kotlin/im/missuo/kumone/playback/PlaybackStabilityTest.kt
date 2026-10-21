// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 播放稳定性判定（引擎熔断判据）矩阵。 */
class PlaybackStabilityTest {

    @Test
    fun `long track played past threshold is stable`() {
        assertTrue(isStablePlayback(positionMs = 5_000, durationMs = 240_000, stableThresholdMs = 5_000))
        assertTrue(isStablePlayback(positionMs = 30_000, durationMs = 240_000, stableThresholdMs = 5_000))
    }

    @Test
    fun `long track that died early is not stable`() {
        assertFalse(isStablePlayback(positionMs = 4_999, durationMs = 240_000, stableThresholdMs = 5_000))
        assertFalse(isStablePlayback(positionMs = 0, durationMs = 240_000, stableThresholdMs = 5_000))
    }

    @Test
    fun `short track played to the end is stable`() {
        // < 5s 的 intro/skit 完整播完：STATE_ENDED 时 ExoPlayer 的 position
        // 精确等于 duration，落在尾部窗口内 → 成功，不应被误判成秒挂坏源
        // （否则含 intro/skit 的专辑播到短曲处会被误判停摆）。
        assertTrue(isStablePlayback(positionMs = 3_000, durationMs = 3_000, stableThresholdMs = 5_000))
        assertTrue(isStablePlayback(positionMs = 1_500, durationMs = 1_500, stableThresholdMs = 5_000))
        assertTrue(isStablePlayback(positionMs = 500, durationMs = 500, stableThresholdMs = 5_000))
    }

    @Test
    fun `short track that died before its end is not stable`() {
        assertFalse(isStablePlayback(positionMs = 2_000, durationMs = 3_000, stableThresholdMs = 5_000))
        assertFalse(isStablePlayback(positionMs = 500, durationMs = 1_500, stableThresholdMs = 5_000))
        // 尾部窗口 min(250ms, 时长/4)：「每次播一半就挂」的坏源过不了窗口。
        assertFalse(isStablePlayback(positionMs = 250, durationMs = 500, stableThresholdMs = 5_000))
        assertFalse(isStablePlayback(positionMs = 3_200, durationMs = 4_000, stableThresholdMs = 5_000))
    }

    @Test
    fun `unknown duration falls back to absolute threshold`() {
        assertTrue(isStablePlayback(positionMs = 5_000, durationMs = 0, stableThresholdMs = 5_000))
        assertFalse(isStablePlayback(positionMs = 4_999, durationMs = 0, stableThresholdMs = 5_000))
    }
}
