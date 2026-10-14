// Ported from kumone by missuo (LGPL-3.0)
//
// 听歌上报结算槽的单测（偏差 ② 的验证）。
//
// 要证明的核心是：切歌瞬间结算上一首时，上报里的 sourceID 必须是**上一首自己的**
// 歌单 ID，而不是结算那一刻的当前歌单。iOS 的答案是
// `PlayerService.swift:3099 let sourceID = source.sourceID`（调用瞬间快照）。
//
// 引擎 `PlaybackEngine` 持有 ExoPlayer 与前台 Service，JVM 里造不出来，所以把
// 这个槽位拆成了纯 Kotlin 的 `ScrobbleLedger`，此处验证的就是快照与清空语义。
//
// 时长语义：take(playedSeconds) 由调用方传入实际已播秒数（ExoPlayer 播放头
// 换算，不含暂停），槽位本身不再依赖挂钟——暂停时长不再被计入上报。

package im.missuo.kumone.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ScrobbleLedgerTest {

    private fun ledger() = ScrobbleLedger()

    /** 播 A（歌单 S1）30 秒后结算：曲目与歌单都必须是 A / S1。 */
    @Test
    fun `finish carries the source snapshot taken at start`() {
        val ledger = ledger()

        ledger.start(trackID = 100L, sourceID = 1L)

        val pending = ledger.take(playedSeconds = 30)
        assertNotNull(pending)
        assertEquals(100L, pending!!.trackID)
        assertEquals(1L, pending.sourceID)
        assertEquals(30, pending.seconds)
    }

    /**
     * 关键回归：A（歌单 S1）与 B（歌单 S2）各自上报自己的 sourceID。
     *
     * 这正是 iOS `:3099` 快照要防的事——若结算时去读「当前 source」，A 的收听
     * 时长就会被记到 S2 头上。
     *
     * 注意这里同时锁死了**调用顺序**：结算必须发生在下一首 start 之前
     * （`playCurrent()` 把 `finishScrobble()` 放在最前面就是为了这个）。槽位只有
     * 一个，`start()` 会覆盖未结算的记录，所以「先结算再开播」不是风格问题，
     * 而是正确性前提。
     */
    @Test
    fun `each track is settled with its own source snapshot`() {
        val ledger = ledger()

        ledger.start(trackID = 100L, sourceID = 1L) // A 在歌单 S1

        // 切歌瞬间：先结算 A（此时槽位里还是 A 与它自己的 S1）。
        val settledA = ledger.take(playedSeconds = 42)
        assertNotNull(settledA)
        assertEquals(100L, settledA!!.trackID)
        assertEquals("A 必须带着开播时快照的 S1", 1L, settledA.sourceID)
        assertEquals(42, settledA.seconds)

        // 再开播 B（队列 source 变 S2），A 已经结清，不会污染 B。
        ledger.start(trackID = 200L, sourceID = 2L)
        val settledB = ledger.take(playedSeconds = 15)
        assertNotNull(settledB)
        assertEquals(200L, settledB!!.trackID)
        assertEquals(2L, settledB.sourceID)
        assertEquals(15, settledB.seconds)
    }

    /** 结算后槽位无条件清空：重复 take 不会重复上报。 */
    @Test
    fun `take clears the slot so it never reports twice`() {
        val ledger = ledger()

        ledger.start(trackID = 100L, sourceID = 1L)

        assertNotNull(ledger.take(playedSeconds = 10))
        assertNull(ledger.take(playedSeconds = 10))
    }

    /** 从未开播就结算（例如启动后直接点下一曲）：安全返回 null。 */
    @Test
    fun `take before any start returns null`() {
        val ledger = ledger()

        assertNull(ledger.take(playedSeconds = 30))
    }

    /** 播放不足 1 秒不结算（现存门槛 `seconds > 0`；iOS 是 `progress > 1`，见类注释）。 */
    @Test
    fun `sub second playback is not reported`() {
        val ledger = ledger()

        ledger.start(trackID = 100L, sourceID = 1L)

        assertNull(ledger.take(playedSeconds = 0))
    }

    /** 未结算就再次开播：旧记录被覆盖，只保留最新的一笔（不产生幽灵上报）。 */
    @Test
    fun `restarting replaces rather than stacks pending records`() {
        val ledger = ledger()

        ledger.start(trackID = 100L, sourceID = 1L)
        ledger.start(trackID = 200L, sourceID = 2L)

        val first = ledger.take(playedSeconds = 15)
        assertEquals(200L, first!!.trackID)
        assertEquals(2L, first.sourceID)
        assertEquals(15, first.seconds)
        assertNull("只剩一笔，不叠加", ledger.take(playedSeconds = 15))
    }
}
