// QA-authored supplemental queue state-machine boundary tests.

package im.missuo.kumone.playback

import im.missuo.kumone.net.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Boundaries the engineer's queue suite leaves uncovered. */
class QaQueueBoundaryTest {

    private fun track(id: Long): Track = Track(id = id, name = "song-$id")
    private fun tracks(count: Int): List<Track> = (1..count).map { track(it.toLong()) }
    private fun queue(): PlaybackQueue = PlaybackQueue()

    // ---- empty queue -----------------------------------------------------

    @Test
    fun `advance on an empty queue returns null`() {
        assertNull(queue().advance())
    }

    @Test
    fun `advance after loading an empty list returns null`() {
        val queue = queue()
        queue.load(emptyList(), startIndex = 0, context = null, source = PlaySource.NONE)
        assertNull(queue.advance())
        assertNull(queue.current())
        assertEquals(-1, queue.currentIndex)
    }

    @Test
    fun `previous on an empty queue returns null`() {
        assertNull(queue().previous())
    }

    @Test
    fun `jumpTo and removeAt on an empty queue are no-ops`() {
        val queue = queue()
        queue.jumpTo(0)
        queue.removeAt(0)
        assertNull(queue.current())
        assertEquals(0, queue.size)
    }

    @Test
    fun `upcoming on an empty queue is empty`() {
        assertTrue(queue().upcoming(5).isEmpty())
    }

    @Test
    fun `upcoming with a non-positive limit is empty`() {
        val queue = queue()
        queue.load(tracks(3), 0, null, PlaySource.PLAYLIST)
        assertTrue(queue.upcoming(0).isEmpty())
        assertTrue(queue.upcoming(-1).isEmpty())
    }

    /** A single-track queue must not walk off the end. */
    @Test
    fun `advance on a single-track queue returns null at the end`() {
        val queue = queue()
        queue.load(tracks(1), 0, null, PlaySource.PLAYLIST)
        assertNotNull(queue.current())
        assertNull("no next track in a one-item queue", queue.advance())
    }

    /** Repeat-one must not advance either; the engine replays. */
    @Test
    fun `repeat one does not advance at the end`() {
        val queue = queue()
        queue.repeatMode = RepeatMode.ONE
        queue.load(tracks(2), 1, null, PlaySource.PLAYLIST)
        assertNull(queue.advance())
        assertEquals("the current track must be unchanged", 1, queue.currentIndex)
    }

    /** Wrap-around must land back on the first track, not out of bounds. */
    @Test
    fun `repeat all wraps from the last track to the first`() {
        val queue = queue()
        queue.repeatMode = RepeatMode.ALL
        queue.load(tracks(3), 2, null, PlaySource.PLAYLIST)
        val next = queue.advance()!!
        assertEquals(1L, next.id)
        assertEquals(0, queue.currentIndex)
    }

    @Test
    fun `previous wraps backwards under repeat all`() {
        val queue = queue()
        queue.repeatMode = RepeatMode.ALL
        queue.load(tracks(3), 0, null, PlaySource.PLAYLIST)
        val previous = queue.previous()!!
        assertEquals(3L, previous.id)
    }

    // ---- FM mode ---------------------------------------------------------

    @Test
    fun `fm mode returns null from both advance and previous`() {
        val queue = queue()
        queue.load(tracks(3), 0, null, PlaySource.PLAYLIST)
        queue.isFMMode = true
        assertNull(queue.advance())
        assertNull(queue.previous())
    }

    @Test
    fun `fm mode still allows insertNext to be queued`() {
        val queue = queue()
        queue.load(tracks(2), 0, null, PlaySource.PLAYLIST)
        queue.isFMMode = true
        queue.insertNext(track(99))
        // advance() is null in FM, but the insertion must not be lost.
        assertTrue(queue.upcoming(5).any { it.id == 99L })
    }

    @Test
    fun `leaving fm mode restores normal advance`() {
        val queue = queue()
        queue.load(tracks(3), 0, null, PlaySource.PLAYLIST)
        queue.isFMMode = true
        queue.isFMMode = false
        assertEquals(2L, queue.advance()!!.id)
    }

    @Test
    fun `load resets fm mode`() {
        val queue = queue()
        queue.isFMMode = true
        queue.load(tracks(2), 0, null, PlaySource.PLAYLIST)
        assertFalse(queue.isFMMode)
    }

    // ---- shuffle ---------------------------------------------------------

    @Test
    fun `shuffle preserves the whole set and puts the current track first`() {
        val queue = queue()
        val list = tracks(8)
        queue.load(list, 3, null, PlaySource.PLAYLIST)
        queue.shuffleEnabled = true

        assertEquals("shuffle must not add or drop tracks", 8, queue.size)
        assertEquals(list.map { it.id }.toSet(), queue.queue.map { it.id }.toSet())
        assertEquals("the current track stays at index 0", list[3].id, queue.queue[0].id)
        assertEquals(0, queue.currentIndex)
    }

    @Test
    fun `shuffle then unshuffle restores the listed order`() {
        val queue = queue()
        val list = tracks(6)
        queue.load(list, 2, null, PlaySource.PLAYLIST)
        queue.shuffleEnabled = true
        queue.shuffleEnabled = false
        assertEquals(list.map { it.id }, queue.queue.map { it.id })
        assertEquals(2, queue.currentIndex)
    }

    /** Insertions must survive a shuffle toggle and still play first. */
    @Test
    fun `insertNext under shuffle plays before the shuffled rest`() {
        val queue = queue()
        queue.load(tracks(5), 0, null, PlaySource.PLAYLIST)
        queue.shuffleEnabled = true
        queue.insertNext(track(777))
        val next = queue.advance()!!
        assertEquals("the inserted track must play first", 777L, next.id)
    }

    @Test
    fun `shuffle is ignored while in fm mode`() {
        val queue = queue()
        queue.load(tracks(4), 1, null, PlaySource.PLAYLIST)
        queue.isFMMode = true
        queue.shuffleEnabled = true
        assertFalse("shuffle must stay off in FM mode", queue.shuffleEnabled)
    }

    @Test
    fun `shuffle on an empty queue does not crash`() {
        val queue = queue()
        queue.shuffleEnabled = true
        assertEquals(0, queue.size)
        assertNull(queue.current())
    }

    /** Repeated toggling must never lose or duplicate a track. */
    @Test
    fun `repeated shuffle toggles keep the set intact`() {
        val queue = queue()
        val list = tracks(7)
        queue.load(list, 0, null, PlaySource.PLAYLIST)
        repeat(5) {
            queue.shuffleEnabled = true
            queue.shuffleEnabled = false
        }
        assertEquals(list.map { it.id }, queue.queue.map { it.id })
    }

    // ---- insertNext / removal -------------------------------------------

    @Test
    fun `insertions are consumed before the queue advances`() {
        val queue = queue()
        queue.load(tracks(3), 0, null, PlaySource.PLAYLIST)
        queue.insertNext(track(50))
        queue.insertNext(track(51))
        assertEquals(50L, queue.advance()!!.id)
        assertEquals(51L, queue.advance()!!.id)
        assertEquals("then the real queue", 2L, queue.advance()!!.id)
    }

    @Test
    fun `an insertion does not move the queue index`() {
        val queue = queue()
        queue.load(tracks(3), 1, null, PlaySource.PLAYLIST)
        queue.insertNext(track(60))
        queue.advance()
        assertEquals("playing an insertion must not advance the queue", 1, queue.currentIndex)
    }

    @Test
    fun `removing the current track is refused in list order`() {
        val queue = queue()
        queue.load(tracks(3), 1, null, PlaySource.PLAYLIST)
        queue.removeAt(1)
        assertEquals("the current track is protected in list order", 3, queue.size)
    }

    @Test
    fun `removing a track before the current shifts the index`() {
        val queue = queue()
        queue.load(tracks(4), 2, null, PlaySource.PLAYLIST)
        queue.removeAt(0)
        assertEquals(1, queue.currentIndex)
        assertEquals(3, queue.size)
    }

    @Test
    fun `out-of-range removal is ignored`() {
        val queue = queue()
        queue.load(tracks(3), 0, null, PlaySource.PLAYLIST)
        queue.removeAt(-1)
        queue.removeAt(99)
        assertEquals(3, queue.size)
    }

    @Test
    fun `jumpTo out of range leaves the index untouched`() {
        val queue = queue()
        queue.load(tracks(3), 1, null, PlaySource.PLAYLIST)
        queue.jumpTo(-5)
        queue.jumpTo(3)
        assertEquals(1, queue.currentIndex)
    }

    /** Removing every non-current track must leave a coherent single-item queue. */
    @Test
    fun `queue can be emptied down to the current track`() {
        val queue = queue()
        queue.load(tracks(4), 0, null, PlaySource.PLAYLIST)
        queue.removeAt(3)
        queue.removeAt(2)
        queue.removeAt(1)
        assertEquals(1, queue.size)
        assertNull("nothing left to advance to", queue.advance())
    }

    // ---- repeat cycling --------------------------------------------------

    @Test
    fun `repeat mode cycles off all one`() {
        val queue = queue()
        assertEquals(RepeatMode.OFF, queue.repeatMode)
        assertEquals(RepeatMode.ALL, queue.repeatMode.next)
        assertEquals(RepeatMode.ONE, RepeatMode.ALL.next)
        assertEquals(RepeatMode.OFF, RepeatMode.ONE.next)
    }

    /** Loading must clear stale insertions (iOS `play(...)` parity). */
    @Test
    fun `load clears pending insertions`() {
        val queue = queue()
        queue.load(tracks(3), 0, null, PlaySource.PLAYLIST)
        queue.insertNext(track(88))
        queue.load(tracks(3), 0, null, PlaySource.PLAYLIST)
        assertEquals("a reload must drop pending insertions", 2L, queue.advance()!!.id)
    }

    /** context/source bookkeeping must survive a load and clear on empty. */
    @Test
    fun `load clears the context when given an empty list`() {
        val queue = queue()
        queue.load(tracks(2), 0, PlayContext.playlist(9L, "x"), PlaySource.PLAYLIST)
        assertNotNull(queue.context)
        queue.load(emptyList(), 0, null, PlaySource.NONE)
        assertNull(queue.context)
        assertEquals(PlaySource.NONE, queue.source)
    }

    /** sourceID must track the loaded context id. */
    @Test
    fun `source id follows the context`() {
        val queue = queue()
        queue.load(tracks(1), 0, PlayContext.album(4242L, "album"), PlaySource.ALBUM)
        assertEquals(4242L, queue.source.sourceID)
    }

    /** A negative start index must clamp to the head rather than crash. */
    @Test
    fun `a negative start index clamps to zero`() {
        val queue = queue()
        queue.load(tracks(3), -7, null, PlaySource.PLAYLIST)
        assertEquals(0, queue.currentIndex)
        assertEquals(1L, queue.current()!!.id)
    }

    /** An out-of-range start index must clamp to the tail. */
    @Test
    fun `an oversized start index clamps to the last track`() {
        val queue = queue()
        queue.load(tracks(3), 99, null, PlaySource.PLAYLIST)
        assertEquals(2, queue.currentIndex)
        assertEquals(3L, queue.current()!!.id)
    }

    /** A one-item queue under shuffle must still start on that item. */
    @Test
    fun `shuffle with a single track keeps it current`() {
        val queue = queue()
        queue.shuffleEnabled = true
        queue.load(tracks(1), 0, null, PlaySource.PLAYLIST)
        assertEquals(0, queue.currentIndex)
        assertEquals(1L, queue.current()!!.id)
    }
}