// QA-authored supplemental lyric-parsing boundary tests.

package im.missuo.kumone.net.lyrics

import im.missuo.kumone.net.model.LyricBody
import im.missuo.kumone.net.model.LyricContributor
import im.missuo.kumone.net.model.LyricResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Boundary conditions around LRC / YRC parsing that the suite did not cover. */
class QaLyricsBoundaryTest {

    // ---- empty / degenerate bodies -------------------------------------

    @Test
    fun `empty lrc body yields empty lyrics`() {
        val parsed = LyricsParser.parse(LyricResponse(lrc = null))
        assertTrue(parsed.isEmpty)
        assertFalse(parsed.isInstrumental)
    }

    @Test
    fun `blank lrc lyric yields empty lyrics`() {
        val parsed = LyricsParser.parse(LyricResponse(lrc = lyric("")))
        assertTrue(parsed.isEmpty)
    }

    @Test
    fun `lrc with no time tags yields empty lyrics`() {
        val parsed = LyricsParser.parse(LyricResponse(lrc = lyric("plain text without tags\nsecond line")))
        assertTrue(parsed.isEmpty)
    }

    @Test
    fun `activeIndex on empty lyrics is null`() {
        assertNull(ParsedLyrics().activeIndex(12.0))
    }

    @Test
    fun `activeIndex before the first line is null`() {
        val lines = listOf(LyricLine(id = 0, time = 10.0, text = "a"))
        assertNull(ParsedLyrics(lines).activeIndex(9.99))
    }

    // ---- instrumental ---------------------------------------------------

    @Test
    fun `instrumental marker with no other lines flags and empties`() {
        val body = "[00:00.00]纯音乐，请欣赏"
        val parsed = LyricsParser.parse(LyricResponse(lrc = lyric(body)))
        assertTrue(parsed.isInstrumental)
        assertTrue(parsed.isEmpty)
    }

    @Test
    fun `credits line is dropped when the track is instrumental`() {
        val body = """
            [00:00.00]纯音乐，请欣赏
            [00:03.00]作词 :Someone
            [00:06.00]作曲 :Another
        """.trimIndent()
        val parsed = LyricsParser.parse(LyricResponse(lrc = lyric(body)))
        assertTrue(parsed.isInstrumental)
        assertTrue("credits must be stripped", parsed.lines.none { it.text.startsWith("作") })
    }

    @Test
    fun `many lines suppress the instrumental flag`() {
        // The iOS rule only treats the track as instrumental when <= 10 lines.
        val body = (0..11).joinToString("\n") { i ->
            "[00:%02d.00]纯音乐，请欣赏".format(i)
        }
        val parsed = LyricsParser.parse(LyricResponse(lrc = lyric(body)))
        assertFalse(
            "with more than 10 lines the marker alone must not flag instrumental",
            parsed.isInstrumental,
        )
    }

    // ---- yrc / word timings ---------------------------------------------

    @Test
    fun `yrc word start and end are monotonic within a line`() {
        val yrc = "[1000,3000](1000,500,0)Hel(1500,500,0)lo(2000,1000,0)world"
        val lines = LyricsParser.parseYRC(yrc)
        assertEquals(1, lines.size)
        val words = lines.first().words!!
        assertEquals(3, words.size)
        for (i in 0 until words.size - 1) {
            assertTrue(
                "word $i start ${words[i].start} must precede word ${i + 1}",
                words[i].start <= words[i + 1].start,
            )
            assertTrue(
                "word $i end ${words[i].end} must not exceed word ${i + 1} start",
                words[i].end <= words[i + 1].start + 0.0001,
            )
        }
        // NetEase verbatim words are concatenated verbatim: the per-word text
        // carries no implicit separator, so "Hel"+"lo"+"world" == "Helloworld".
        assertEquals("Helloworld", lines.first().text)
        assertEquals("Hel", words[0].text)
        assertEquals("world", words[2].text)
    }

    @Test
    fun `yrc word end equals start plus duration`() {
        val yrc = "[0,2000](250,500,0)a(750,250,0)b"
        val words = LyricsParser.parseYRC(yrc).first().words!!
        assertEquals(0.25, words[0].start, 1e-9)
        assertEquals(0.75, words[0].end, 1e-9)
        assertEquals(0.75, words[1].start, 1e-9)
        assertEquals(1.0, words[1].end, 1e-9)
    }

    @Test
    fun `yrc metadata header lines are skipped`() {
        val yrc = """
            {"t":0,"c":[{"tx":"hi"}]}
            [0,1000](0,1000,0)real line
        """.trimIndent()
        val lines = LyricsParser.parseYRC(yrc)
        assertEquals(1, lines.size)
        assertEquals("real line", lines.first().text)
    }

    @Test
    fun `yrc line with no words is dropped`() {
        val yrc = "[0,1000]\n[1000,1000](1000,500,0)ok"
        val lines = LyricsParser.parseYRC(yrc)
        assertEquals(1, lines.size)
        assertEquals("ok", lines.first().text)
    }

    @Test
    fun `large yrc timestamps convert from milliseconds`() {
        val yrc = "[600000,2000](600000,500,0)late"
        val line = LyricsParser.parseYRC(yrc).single()
        assertEquals(600.0, line.time, 1e-9)
        assertEquals(600.0, line.words!!.first().start, 1e-9)
    }

    @Test
    fun `yrc is preferred over lrc when both are present`() {
        val response = LyricResponse(
            lrc = lyric("[00:00.00]lrc line"),
            yrc = lyric("[0,1000](0,1000,0)yrc line"),
        )
        val parsed = LyricsParser.parse(response)
        assertEquals("yrc line", parsed.lines.first().text)
        assertNotNull("yrc lines carry word timings", parsed.lines.first().words)
    }

    @Test
    fun `unparseable yrc falls back to lrc lines`() {
        val response = LyricResponse(
            lrc = lyric("[00:00.00]fallback line"),
            yrc = lyric("total garbage, no tags at all"),
        )
        val parsed = LyricsParser.parse(response)
        assertEquals("fallback line", parsed.lines.first().text)
        assertNull(parsed.lines.first().words)
    }

    // ---- translation / romaji fallback ----------------------------------

    @Test
    fun `tlyric translation is merged onto the nearest line`() {
        val response = LyricResponse(
            lrc = lyric("[00:01.00]hello"),
            tlyric = lyric("[00:01.00]你好"),
        )
        val parsed = LyricsParser.parse(response)
        assertEquals("hello", parsed.lines.first().text)
        assertEquals("你好", parsed.lines.first().translation)
    }

    @Test
    fun `verbatim ytlrc translation is merged too`() {
        val response = LyricResponse(
            lrc = lyric("[00:01.00]hello"),
            yrc = lyric("[1000,1000](1000,1000,0)hello"),
            ytlrc = lyric("[1000,1000](1000,1000,0)你好"),
        )
        val parsed = LyricsParser.parse(response)
        assertEquals("hello", parsed.lines.first().text)
        assertEquals("你好", parsed.lines.first().translation)
    }

    @Test
    fun `romalrc is merged as romaji`() {
        val response = LyricResponse(
            lrc = lyric("[00:02.00]konnichiwa"),
            romalrc = lyric("[00:02.00]こんにちは"),
        )
        val parsed = LyricsParser.parse(response)
        assertEquals("こんにちは", parsed.lines.first().romaji)
    }

    @Test
    fun `a translation beyond the merge window is not attached`() {
        val response = LyricResponse(
            lrc = lyric("[00:01.00]hello"),
            // 5 s away — far beyond the 0.3 s window.
            tlyric = lyric("[00:06.00]你好"),
        )
        val parsed = LyricsParser.parse(response)
        assertNull(parsed.lines.first().translation)
    }

    @Test
    fun `absent translation and romaji leave the fields null`() {
        val parsed = LyricsParser.parse(LyricResponse(lrc = lyric("[00:01.00]solo")))
        assertNull(parsed.lines.first().translation)
        assertNull(parsed.lines.first().romaji)
    }

    // ---- multi-timestamp / multi-singer ---------------------------------

    @Test
    fun `one line with several timestamps expands to several entries`() {
        val raw = LyricsParser.parseLRC("[00:01.00][00:05.00]repeated")
        assertEquals(2, raw.size)
        assertEquals(1.0, raw[0].time, 1e-9)
        assertEquals(5.0, raw[1].time, 1e-9)
    }

    @Test
    fun `results are sorted by time regardless of input order`() {
        val raw = LyricsParser.parseLRC("[00:09.00]late\n[00:01.00]early\n[00:05.00]middle")
        assertEquals(listOf(1.0, 5.0, 9.0), raw.map { it.time })
    }

    @Test
    fun `colon and dot millisecond separators agree`() {
        val dot = LyricsParser.parseLRC("[00:01.50]x").single().time
        val colon = LyricsParser.parseLRC("[00:01:50]x").single().time
        assertEquals(1.5, dot, 1e-9)
        assertEquals(1.5, colon, 1e-9)
    }

    @Test
    fun `a huge timestamp is preserved`() {
        val raw = LyricsParser.parseLRC("[99:59.99]end")
        assertEquals(5999.99, raw.single().time, 1e-6)
    }

    @Test
    fun `multiple singers on one line are preserved verbatim`() {
        val raw = LyricsParser.parseLRC("[00:01.00]A & B")
        assertEquals("A & B", raw.single().text)
    }

    // ---- contributors ----------------------------------------------------

    @Test
    fun `contributors are surfaced`() {
        val response = LyricResponse(
            lrc = lyric("[00:01.00]x"),
            lyricUser = lyricUser("Alice"),
            transUser = lyricUser("Bob"),
        )
        val parsed = LyricsParser.parse(response)
        assertEquals("Alice", parsed.contributor)
        assertEquals("Bob", parsed.translationContributor)
    }

    @Test
    fun `empty lrc body still reports contributors`() {
        val response = LyricResponse(
            lrc = lyric(""),
            lyricUser = lyricUser("Alice"),
            transUser = lyricUser("Bob"),
        )
        val parsed = LyricsParser.parse(response)
        assertTrue(parsed.isEmpty)
        assertEquals("Alice", parsed.contributor)
        assertEquals("Bob", parsed.translationContributor)
    }
}

// Use the production model types directly — no local stubs.
private fun lyric(text: String) = LyricBody(text)

private fun lyricUser(nickname: String) = LyricContributor(nickname)