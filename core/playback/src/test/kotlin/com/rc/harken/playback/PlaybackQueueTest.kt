// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken.playback

import com.rc.harken.net.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Queue state-machine matrix: 循环 / 随机 / FM / 插播 / 删除 / advance. */
class PlaybackQueueTest {

    private fun track(id: Long): Track = Track(id = id, name = "song-$id")

    private fun tracks(count: Int): List<Track> = (1..count).map { track(it.toLong()) }

    private fun queue(): PlaybackQueue = PlaybackQueue()

    // MARK: - load / basics

    @Test
    fun `load sets queue index context and source`() {
        val queue = queue()
        val list = tracks(3)

        queue.load(list, startIndex = 1, context = PlayContext.playlist(123L, "歌单"), source = PlaySource.PLAYLIST)

        assertEquals(3, queue.size)
        assertEquals(1, queue.currentIndex)
        assertEquals(list[1], queue.current())
        assertEquals(PlayContext.Kind.PLAYLIST, queue.context?.kind)
        assertEquals(123L, queue.context?.id)
        assertEquals(PlaySource.PLAYLIST, queue.source)
        assertEquals(123L, queue.source.sourceID)
        assertTrue(!queue.isFMMode)
    }

    @Test
    fun `load clamps the start index and clears on empty`() {
        val queue = queue()
        val list = tracks(3)

        queue.load(list, startIndex = 99, context = null, source = PlaySource.NONE)
        assertEquals(2, queue.currentIndex)

        queue.load(emptyList(), startIndex = 0, context = PlayContext.fm, source = PlaySource.NONE)
        assertEquals(0, queue.size)
        assertEquals(-1, queue.currentIndex)
        assertNull(queue.current())
        assertNull(queue.context?.let { it })
        assertEquals(0L, queue.source.sourceID)
    }

    @Test
    fun `load resets fm mode and insertions`() {
        val queue = queue()
        queue.load(emptyList(), 0, PlayContext.fm, PlaySource.NONE)
        queue.isFMMode = true
        queue.insertNext(track(50))

        queue.load(tracks(2), 0, null, PlaySource.NONE)

        assertTrue(!queue.isFMMode)
        // The insertion is gone; upcoming is the queue rest after the current
        // track (iOS `upcomingTracks` = playNext + activeQueue[current+1…]).
        assertEquals(listOf(track(2)), queue.upcoming(10))
    }

    // MARK: - advance / repeat

    @Test
    fun `advance walks the queue and stops at the end when repeat is off`() {
        val queue = queue()
        val list = tracks(3)
        queue.load(list, 0, null, PlaySource.NONE)

        assertEquals(list[1], queue.advance())
        assertEquals(list[2], queue.advance())
        assertNull(queue.advance())
        assertEquals(2, queue.currentIndex)
        assertEquals(list[2], queue.current())
    }

    @Test
    fun `advance wraps with repeat all`() {
        val queue = queue()
        val list = tracks(3)
        queue.load(list, 2, null, PlaySource.NONE)
        queue.repeatMode = RepeatMode.ALL

        assertEquals(list[0], queue.advance())
        assertEquals(list[1], queue.advance())
        assertEquals(list[2], queue.advance())
        assertEquals(list[0], queue.advance())
    }

    @Test
    fun `repeat one stops advance at the end for the engine to replay`() {
        val queue = queue()
        val list = tracks(2)
        queue.load(list, 1, null, PlaySource.NONE)
        queue.repeatMode = RepeatMode.ONE

        assertNull(queue.advance())
        assertEquals(list[1], queue.current())
    }

    @Test
    fun `previous walks back and stops at the head without repeat all`() {
        val queue = queue()
        val list = tracks(3)
        queue.load(list, 2, null, PlaySource.NONE)

        assertEquals(list[1], queue.previous())
        assertEquals(list[0], queue.previous())
        assertNull(queue.previous())
        assertEquals(0, queue.currentIndex)

        queue.repeatMode = RepeatMode.ALL
        assertEquals(list[2], queue.previous())
    }

    // MARK: - play-next insertions

    @Test
    fun `inserted tracks play before the queue rest`() {
        val queue = queue()
        val list = tracks(3)
        queue.load(list, 0, null, PlaySource.NONE)
        val inserted = track(99)

        queue.insertNext(inserted)

        assertEquals(listOf(inserted, list[1], list[2]), queue.upcoming(10))
        // The insertion keeps the queue index; the engine plays it directly.
        assertEquals(inserted, queue.advance())
        assertEquals(0, queue.currentIndex)
        assertEquals(inserted, queue.current())
        assertEquals(list[1], queue.advance())
        assertEquals(1, queue.currentIndex)
    }

    @Test
    fun `insertions are first in first out`() {
        val queue = queue()
        queue.load(tracks(2), 0, null, PlaySource.NONE)
        val first = track(91)
        val second = track(92)

        queue.insertNext(first)
        queue.insertNext(second)

        assertEquals(first, queue.advance())
        assertEquals(second, queue.advance())
        assertEquals(track(2), queue.advance())
    }

    // MARK: - shuffle

    @Test
    fun `shuffle keeps the current track first and preserves the set`() {
        val queue = queue()
        val list = tracks(8)
        queue.load(list, 3, null, PlaySource.NONE)
        val current = queue.current()!!

        queue.shuffleEnabled = true

        assertEquals(0, queue.currentIndex)
        assertEquals(current, queue.current())
        assertEquals(8, queue.size)
        assertEquals(list.map { it.id }.toSet(), queue.queue.map { it.id }.toSet())
    }

    @Test
    fun `load under shuffle starts at the requested track`() {
        val queue = queue()
        queue.shuffleEnabled = true
        val list = tracks(6)

        queue.load(list, startIndex = 4, context = null, source = PlaySource.NONE)

        assertEquals(0, queue.currentIndex)
        assertEquals(list[4], queue.current())
        assertEquals(6, queue.size)
        assertEquals(list.map { it.id }.toSet(), queue.queue.map { it.id }.toSet())
    }

    @Test
    fun `disabling shuffle restores the listed order`() {
        val queue = queue()
        val list = tracks(4)
        queue.load(list, 0, null, PlaySource.NONE)
        queue.shuffleEnabled = true
        queue.shuffleEnabled = false

        // The listed order is what load() stored; advancing follows it.
        assertEquals(list[1], queue.advance())
        assertEquals(list[2], queue.advance())
    }

    /**
     * 基准曲不在 listedOrder 时关闭随机必须回落到队首，而不是把 currentIndex 置 -1。
     *
     * 构造方式（公共 API，无需反射）：load → 开随机 → insertNext 一首不在表内的
     * 曲目并 advance() 让它发声（此时 currentOverride = 插播曲）→ 关随机。
     * 关随机时基准曲 = 插播曲，其 id 不在 listedOrder 里 → 走回落分支。
     *
     * 修复前该分支置 currentIndex = -1：current() 仍返回插播曲（override 未清），
     * 但 advance() 因 `currentIndex < 0` 恒返回 null，上一首/自动续播同样失效。
     */
    @Test
    fun `disabling shuffle falls back to the queue head when the base track is missing`() {
        val queue = queue()
        val list = tracks(3)
        queue.load(list, startIndex = 0, context = null, source = PlaySource.NONE)
        queue.shuffleEnabled = true
        val inserted = track(99)
        queue.insertNext(inserted)
        // 插播曲发声：currentOverride = 插播曲，队列索引留在原位。
        assertEquals(inserted, queue.advance())
        assertEquals(0, queue.currentIndex)

        queue.shuffleEnabled = false

        // 插播曲仍在发声，不能被清掉。
        assertEquals(inserted, queue.current())
        // 基准曲（插播曲）不在 listedOrder → 回落到队首（不是 -1）。
        assertEquals(0, queue.currentIndex)
        assertEquals(list, queue.queue)
        // 插播曲播完后顺着恢复的顺序继续：队首已作为位置基准，下一首是第 2 首。
        assertEquals(list[1], queue.advance())
        assertEquals(1, queue.currentIndex)
        // 上一首也必须可用（同受 `currentIndex < 0` 守卫影响）。
        assertEquals(list[0], queue.previous())
    }

    /**
     * 回落分支同样不能让待播的插播曲丢失，且 repeat-all 的绕回不能被破坏。
     */
    @Test
    fun `disabling shuffle keeps pending insertions and repeat all working after the fallback`() {
        val queue = queue()
        val list = tracks(2)
        queue.load(list, startIndex = 0, context = null, source = PlaySource.NONE)
        queue.shuffleEnabled = true
        val inserted = track(77)
        queue.insertNext(inserted)
        queue.insertNext(track(78))
        assertEquals(inserted, queue.advance())
        queue.repeatMode = RepeatMode.ALL

        queue.shuffleEnabled = false

        assertEquals(inserted, queue.current())
        assertEquals(0, queue.currentIndex)
        // 待播插播曲先走，然后从恢复后的队列继续，最后照 repeat-all 绕回队首。
        assertEquals(track(78), queue.advance())
        assertEquals(list[1], queue.advance())
        assertEquals(list[0], queue.advance())
        assertEquals(0, queue.currentIndex)
    }

    /**
     * 回归：插播曲的 id 仍能在 listedOrder 里定位时，走的是「定位到基准曲位置」的
     * 原有分支，不能被新加的回落逻辑改掉。
     */
    @Test
    fun `disabling shuffle relocates a sounding insertion whose id is still listed`() {
        val queue = queue()
        val list = tracks(3)
        queue.load(list, startIndex = 0, context = null, source = PlaySource.NONE)
        queue.shuffleEnabled = true
        // 插播曲与表内第 2 首同 id：基准曲能在 listedOrder 里定位到。
        queue.insertNext(track(2))
        assertEquals(2L, queue.advance()!!.id)

        queue.shuffleEnabled = false

        assertEquals(2L, queue.current()!!.id)
        assertEquals(1, queue.currentIndex)
        // 从基准曲的下一首继续。
        assertEquals(3L, queue.advance()!!.id)
    }

    /**
     * listedOrder 为空时保持既有语义：没有可播内容，currentIndex 回到 -1、
     * current()/advance()/previous() 都返回 null。
     */
    @Test
    fun `disabling shuffle on an empty listed order keeps no current track`() {
        val queue = queue()
        queue.load(tracks(2), startIndex = 0, context = null, source = PlaySource.NONE)
        queue.shuffleEnabled = true
        // 队列被清空，listedOrder 也随之清空。
        queue.load(emptyList(), startIndex = 0, context = null, source = PlaySource.NONE)

        queue.shuffleEnabled = false

        assertEquals(-1, queue.currentIndex)
        assertTrue(queue.queue.isEmpty())
        assertNull(queue.current())
        assertNull(queue.advance())
        assertNull(queue.previous())
    }

    // MARK: - FM

    @Test
    fun `fm mode disables previous and advance`() {
        val queue = queue()
        queue.load(emptyList(), 0, PlayContext.fm, PlaySource.NONE)
        queue.isFMMode = true

        assertNull(queue.advance())
        assertNull(queue.previous())
        assertNull(queue.current())
    }

    @Test
    fun `fm mode ignores shuffle toggles`() {
        val queue = queue()
        queue.load(tracks(3), 0, null, PlaySource.NONE)
        queue.isFMMode = true
        val before = queue.queue

        queue.shuffleEnabled = true

        assertEquals(before, queue.queue)
    }

    // MARK: - jump / remove / upcoming

    @Test
    fun `jumpTo moves within the queue and ignores out of range`() {
        val queue = queue()
        val list = tracks(3)
        queue.load(list, 0, null, PlaySource.NONE)

        queue.jumpTo(2)
        assertEquals(list[2], queue.current())
        queue.jumpTo(-1)
        assertEquals(list[2], queue.current())
        queue.jumpTo(99)
        assertEquals(list[2], queue.current())
    }

    @Test
    fun `removeAt shifts indices and protects the current track in list order`() {
        val queue = queue()
        val list = tracks(4)
        queue.load(list, 2, null, PlaySource.NONE)

        // The current track cannot be removed in list order.
        queue.removeAt(2)
        assertEquals(4, queue.size)

        // Removing a later entry keeps the current index.
        queue.removeAt(3)
        assertEquals(3, queue.size)
        assertEquals(2, queue.currentIndex)
        assertEquals(list[2], queue.current())

        // Removing an earlier entry shifts the current index down.
        queue.removeAt(0)
        assertEquals(2, queue.size)
        assertEquals(1, queue.currentIndex)
        assertEquals(list[2], queue.current())
        assertEquals(listOf(list[1], list[2]), queue.queue)
    }

    @Test
    fun `removeAt can remove the current track in shuffle order`() {
        val queue = queue()
        val list = tracks(3)
        queue.load(list, 0, null, PlaySource.NONE)
        queue.shuffleEnabled = true
        // Snapshot the (random) shuffled order so the assertion is stable.
        val shuffled = queue.queue

        queue.removeAt(0)

        assertEquals(2, queue.size)
        assertEquals(0, queue.currentIndex)
        // The following track takes over.
        assertEquals(shuffled[1], queue.current())
    }

    /**
     * 随机序下删掉「当前曲且它正好是最后一首」：删前 currentIndex == lastIndex，
     * 删后若不钳制就会停在 queue.size（越界）——current() 变 null、「下一首」假死。
     * 钳回 lastIndex 后：当前曲是原倒数第二首，上一首仍可走。
     */
    @Test
    fun `removeAt on the last current track clamps the index instead of going out of range`() {
        val queue = queue()
        val list = tracks(3)
        queue.load(list, startIndex = 0, context = null, source = PlaySource.NONE)
        queue.shuffleEnabled = true
        val shuffled = queue.queue
        queue.jumpTo(shuffled.lastIndex)
        val expectedPrevious = shuffled[shuffled.lastIndex - 1]

        queue.removeAt(shuffled.lastIndex)

        assertEquals(2, queue.size)
        // 越界的 currentIndex（== size）被钳回合法范围。
        assertEquals(queue.queue.lastIndex, queue.currentIndex)
        assertEquals(1, queue.currentIndex)
        assertEquals(expectedPrevious, queue.current())
        // 顺序播放真的到底了：OFF 下「下一首」返回 null 是正常语义。
        assertNull(queue.advance())
        // 但索引没越界，「上一首」必须还能走。
        assertEquals(shuffled[0], queue.previous())
    }

    /** 随机序连删到底：队列空 → currentIndex 回到 -1（无当前曲）。 */
    @Test
    fun `removing every track in shuffle order leaves no current track`() {
        val queue = queue()
        queue.load(tracks(3), startIndex = 0, context = null, source = PlaySource.NONE)
        queue.shuffleEnabled = true

        queue.removeAt(0)
        queue.removeAt(0)
        queue.removeAt(0)

        assertEquals(0, queue.size)
        assertEquals(-1, queue.currentIndex)
        assertNull(queue.current())
        assertNull(queue.advance())
        assertNull(queue.previous())
    }

    /**
     * 列表序回归：删当前曲仍被拒绝；删当前曲之前的一首索引自减 1；
     * 删当前曲之后的一首索引不动——末尾的钳制逻辑都不能改坏这三条。
     */
    @Test
    fun `removing in list order keeps the current index correct and protects the current track`() {
        val queue = queue()
        val list = tracks(4)
        queue.load(list, startIndex = 2, context = null, source = PlaySource.NONE)

        // 列表序不允许删当前曲（无副作用）。
        queue.removeAt(2)
        assertEquals(4, queue.size)
        assertEquals(2, queue.currentIndex)
        assertEquals(list[2], queue.current())

        // 删当前曲之前的一首 → 索引自减 1。
        queue.removeAt(0)
        assertEquals(3, queue.size)
        assertEquals(1, queue.currentIndex)
        assertEquals(list[2], queue.current())

        // 删当前曲之后的一首 → 索引不动。
        queue.removeAt(queue.queue.lastIndex)
        assertEquals(2, queue.size)
        assertEquals(1, queue.currentIndex)
        assertEquals(list[2], queue.current())
    }

    @Test
    fun `upcoming respects the limit and skips the current track`() {
        val queue = queue()
        val list = tracks(5)
        queue.load(list, 1, null, PlaySource.NONE)

        assertEquals(listOf(list[2], list[3], list[4]), queue.upcoming(10))
        assertEquals(listOf(list[2], list[3]), queue.upcoming(2))
        assertTrue(queue.upcoming(0).isEmpty())
    }

    @Test
    fun `repeat mode cycles off all one`() {
        assertEquals(RepeatMode.ALL, RepeatMode.OFF.next)
        assertEquals(RepeatMode.ONE, RepeatMode.ALL.next)
        assertEquals(RepeatMode.OFF, RepeatMode.ONE.next)
    }

    @Test
    fun `audio quality maps to api levels`() {
        assertEquals("standard", AudioQuality.STANDARD.apiLevel)
        assertEquals("higher", AudioQuality.HIGHER.apiLevel)
        assertEquals("exhigh", AudioQuality.EXHIGH.apiLevel)
        assertEquals("lossless", AudioQuality.LOSSLESS.apiLevel)
        assertEquals("hires", AudioQuality.HIRES.apiLevel)
    }

    // MARK: - move (队列拖拽排序)

    @Test
    fun `move keeps current index when current track is the one moved`() {
        val queue = queue()
        val list = tracks(4)
        queue.load(list, startIndex = 1, context = null, source = PlaySource.NONE)

        queue.move(from = 1, to = 3)

        assertEquals(listOf(list[0], list[2], list[3], list[1]), queue.queue)
        assertEquals(3, queue.currentIndex)
        assertEquals(list[1], queue.current())
    }

    @Test
    fun `move decrements current index when a track before current crosses it`() {
        val queue = queue()
        val list = tracks(4)
        queue.load(list, startIndex = 2, context = null, source = PlaySource.NONE)

        queue.move(from = 1, to = 3)

        assertEquals(listOf(list[0], list[2], list[3], list[1]), queue.queue)
        assertEquals(1, queue.currentIndex)
        assertEquals(list[2], queue.current())
    }

    @Test
    fun `move increments current index when a track after current crosses it`() {
        val queue = queue()
        val list = tracks(4)
        queue.load(list, startIndex = 1, context = null, source = PlaySource.NONE)

        queue.move(from = 3, to = 0)

        assertEquals(listOf(list[3], list[0], list[1], list[2]), queue.queue)
        assertEquals(2, queue.currentIndex)
        assertEquals(list[1], queue.current())
    }

    @Test
    fun `move ignores out of range and no op moves`() {
        val queue = queue()
        val list = tracks(3)
        queue.load(list, startIndex = 1, context = null, source = PlaySource.NONE)
        val before = queue.queue

        queue.move(from = 0, to = 0)
        queue.move(from = -1, to = 1)
        queue.move(from = 1, to = 3)
        queue.move(from = 3, to = 0)

        assertEquals(before, queue.queue)
        assertEquals(1, queue.currentIndex)
    }

    @Test
    fun `move keeps listed order in sync for shuffle restore`() {
        val queue = queue()
        val list = tracks(4)
        queue.load(list, startIndex = 1, context = null, source = PlaySource.NONE)

        queue.move(from = 1, to = 3)
        // 关闭随机恢复原始顺序：move 同步改动了 listedOrder，恢复后仍是 move 后的顺序。
        queue.shuffleEnabled = true
        queue.shuffleEnabled = false

        assertEquals(listOf(list[0], list[2], list[3], list[1]), queue.queue)
        assertEquals(3, queue.currentIndex)
    }
}
