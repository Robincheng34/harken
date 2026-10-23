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
