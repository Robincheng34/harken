// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.player

import im.missuo.kumone.net.api.NeteaseApi
import kotlinx.coroutines.CancellationException

/**
 * 播放上报（iOS weblog 语义）。
 *
 * - `startplay`：开播即报（写入「最近播放」）。
 * - `play`：带已播秒数（听歌排行/时长统计）。
 *
 * 上报为 best-effort：网络失败静默丢弃，绝不影响播放。
 */
class Scrobbler(private val api: NeteaseApi) {

    suspend fun start(trackID: Long, sourceID: Long) {
        try {
            api.scrobbleStart(trackID, sourceID)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            // Best-effort: scrobbling must never interrupt playback.
        }
    }

    suspend fun finish(trackID: Long, sourceID: Long, seconds: Int) {
        try {
            api.scrobbleFinish(trackID, sourceID, seconds)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            // Best-effort.
        }
    }
}
