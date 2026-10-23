// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken.player

/**
 * 待结算的听歌上报（一次 [ScrobbleLedger.take] 的产物）。
 *
 * [sourceID] 是**开播那一刻**的快照，不是结算那一刻的当前歌单 ID——详见
 * [ScrobbleLedger] 的说明。
 */
data class PendingScrobble(
    val trackID: Long,
    val sourceID: Long,
    val seconds: Int,
)

/**
 * 听歌上报（scrobble）的「结算槽」（iOS `PlayerService.scrobbleIfNeeded`）。
 *
 * **为什么必须快照 sourceID**：iOS `PlayerService.swift:3095-3103` 在结算那一刻
 * 把 `source.sourceID` 拷进局部变量再交给 `Task.detached`：
 *
 * ```
 * 3095: private func scrobbleIfNeeded(completed: Bool) {
 * 3096:     guard let track = currentTrack, !scrobbled, progress > 1 else { return }
 * 3097:     scrobbled = true
 * 3098:     let seconds = completed ? Int(duration) : Int(progress)
 * 3099:     let sourceID = source.sourceID          // ← 快照
 * 3100:     Task.detached {
 * 3101:         await NeteaseAPI.scrobbleFinish(trackID: track.id, sourceID: sourceID, seconds: seconds)
 * 3102:     }
 * 3103: }
 * ```
 *
 * 而它的调用点 `:960` `scrobbleIfNeeded(completed: false)` 位于 `:961`
 * `currentTrack = track` **之前**——也就是说 iOS 是「切歌瞬间结算上一首」，此时
 * 队列已经切到新歌单了。若结算时去读「当前 source」，上一首的收听时长就会被记到
 * 下一首所属歌单头上（手动点下一曲时必现）。
 *
 * 本类把这个「开播时写、结算时读并清空」的槽位独立出来，一是让快照语义显式化，
 * 二是引擎本身持有 ExoPlayer/Service，JVM 里造不出来，抽出来才能单测。
 *
 * 与 iOS 的一处已知差异：iOS 的门槛是 `progress > 1`（至少播满 2 秒），本实现的
 * 门槛是 `seconds > 0`（至少 1 秒）——这是移植时既有的行为，本轮不改。
 */
class ScrobbleLedger {

    private var trackID: Long? = null
    private var sourceID: Long? = null

    /** 开播：记录「谁、在哪个歌单」，覆盖上一笔未结算记录。 */
    fun start(trackID: Long, sourceID: Long) {
        this.trackID = trackID
        this.sourceID = sourceID
    }

    /**
     * 结算并**无条件清空**槽位（成功与否都清空，避免重复上报）。
     *
     * 原 bug：此处用挂钟差值（`clock() - startedAtMs`）计时，暂停时长也被计入
     * 听歌上报。已改为由调用方传入实际已播秒数（`exoPlayer.currentPosition / 1000`）。
     *
     * @param playedSeconds 实际已播秒数（不含暂停）。
     * @return 待上报记录；无记录或播放时长不足 1 秒时返回 null。
     */
    fun take(playedSeconds: Int): PendingScrobble? {
        val pendingTrackID = trackID
        val pendingSourceID = sourceID
        trackID = null
        sourceID = null
        if (pendingTrackID == null || pendingSourceID == null) return null
        if (playedSeconds <= 0) return null
        return PendingScrobble(
            trackID = pendingTrackID,
            sourceID = pendingSourceID,
            seconds = playedSeconds,
        )
    }
}
