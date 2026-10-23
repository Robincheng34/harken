// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken.net.lyrics

import com.rc.harken.net.client.NeteaseJson
import com.rc.harken.net.model.LyricResponse
import java.io.FileNotFoundException
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** LRC / YRC / translation-merge parsing tests, driven by a recorded fixture. */
class LyricsParserTest {

    private val json = NeteaseJson

    private fun fixture(name: String): String {
        val stream = javaClass.getResourceAsStream("/fixtures/$name")
            ?: throw FileNotFoundException("missing test resource: fixtures/$name")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun lyricResponse(raw: String): LyricResponse =
        json.decodeFromString(raw)

    @Test
    fun `parseLRC handles multi timestamps and both millis separators`() {
        val lrc = """
            [00:12.50]第一个时间
            [00:12:50]冒号毫秒
            [00:20.500]三位毫秒
            [00:01.00][01:20.00]重复的歌词
            [ti:元数据]
            没有时间戳的行
        """.trimIndent()

        val lines = LyricsParser.parseLRC(lrc)

        // Sorted by time (iOS parity): the two-tag line yields 1.0 and 80.0.
        assertEquals(1.0, lines[0].time, 1e-9)
        assertEquals("重复的歌词", lines[0].text)
        assertEquals(12.5, lines[1].time, 1e-9)
        assertEquals("第一个时间", lines[1].text)
        assertEquals(12.5, lines[2].time, 1e-9)
        assertEquals("冒号毫秒", lines[2].text)
        assertEquals(20.5, lines[3].time, 1e-9)
        assertEquals("三位毫秒", lines[3].text)
        assertEquals(80.0, lines[4].time, 1e-9)
        assertEquals("重复的歌词", lines[4].text)
        // Sorted ascending.
        assertEquals(lines.sortedBy { it.time }, lines)
    }

    @Test
    fun `parseYRC extracts word timings and skips metadata`() {
        val yrc = """
            [{"t":0,"c":[{"tx":"作词 : someone"}]}]
            [12500,6800](12500,600,0)今天(13100,500,0)我(13600,700,0)寒(14300,500,0)夜(14800,400,0)里
            [19300,6800](19300,400,0)怀着(19700,600,0)冷(20300,500,0)却(20800,500,0)了
            [30000,1000](30000,1000,0)
        """.trimIndent()

        val lines = LyricsParser.parseYRC(yrc)

        assertEquals(2, lines.size)
        assertEquals(12.5, lines[0].time, 1e-9)
        assertEquals("今天我寒夜里", lines[0].text)
        assertEquals(5, lines[0].words!!.size)
        assertEquals("今天", lines[0].words!![0].text)
        assertEquals(12.5, lines[0].words!![0].start, 1e-9)
        assertEquals(0.6, lines[0].words!![0].duration, 1e-9)
        assertEquals(13.1, lines[0].words!![0].end, 1e-9)
        assertEquals("怀着冷却了", lines[1].text)
        assertEquals(0, lines[0].id)
        assertEquals(1, lines[1].id)
    }

    @Test
    fun `parse prefers yrc and merges translation and romaji`() {
        val response = lyricResponse(fixture("lyric-lrc-yrc.json"))

        val parsed = LyricsParser.parse(response)

        // Verbatim lines replace the lrc lines (metadata/credits dropped).
        assertEquals(3, parsed.lines.size)
        assertEquals("今天我寒夜里看雪飘过", parsed.lines[0].text)
        assertNotNull(parsed.lines[0].words)
        assertEquals(12.5, parsed.lines[0].time, 1e-9)
        assertEquals("harken_tester", parsed.contributor)
        assertEquals("translator", parsed.translationContributor)
        // The fixture lrc has 9 timestamped lines (<= 10) including the
        // instrumental marker, so iOS marks the track instrumental even
        // though the verbatim yrc lines replace the lrc lines below.
        assertTrue(parsed.isInstrumental)

        // Translation from the verbatim ytlric body (yrc fallback parse).
        assertEquals("Today I watch the snow drift by in the cold night", parsed.lines[0].translation)
        assertEquals("With a cooled heart I drift far away to distant lands", parsed.lines[1].translation)
        // No secondary line within 0.3s of the third main line.
        assertNull(parsed.lines[2].translation)

        // Romaji from the verbatim yromalrc body (word pieces concatenated
        // exactly as the fixture records them — no space before "yè").
        assertEquals("jīntiān wǒ hányè lǐ kàn xuě piāo guò", parsed.lines[0].romaji)
        assertEquals("huái zhe lěng què le de xīn wō piāo yuǎn fāng", parsed.lines[1].romaji)
        assertNull(parsed.lines[2].romaji)
    }

    @Test
    fun `activeIndex binary searches the active line`() {
        val parsed = LyricsParser.parse(lyricResponse(fixture("lyric-lrc-yrc.json")))

        assertNull(parsed.activeIndex(position = 10.0))
        assertEquals(0, parsed.activeIndex(position = 12.5))
        assertEquals(0, parsed.activeIndex(position = 15.0))
        assertEquals(1, parsed.activeIndex(position = 19.3))
        assertEquals(2, parsed.activeIndex(position = 26.1))
        assertEquals(2, parsed.activeIndex(position = 999.0))
    }

    @Test
    fun `pure instrumental lyrics are flagged and emptied`() {
        val response = lyricResponse(
            """{"lrc":{"lyric":"[00:01.00]纯音乐，请欣赏\n[00:01.00]作词: 无\n[00:01.00]作曲: 无"}}""",
        )

        val parsed = LyricsParser.parse(response)

        assertTrue(parsed.isInstrumental)
        assertTrue(parsed.isEmpty)
    }

    @Test
    fun `instrumental marker keeps the remaining lines`() {
        val response = lyricResponse(
            """{"lrc":{"lyric":"[00:01.00]纯音乐，请欣赏\n[00:02.00]真实的歌词"}}""",
        )

        val parsed = LyricsParser.parse(response)

        assertTrue(parsed.isInstrumental)
        assertEquals(1, parsed.lines.size)
        assertEquals("真实的歌词", parsed.lines[0].text)
    }

    @Test
    fun `empty credit lines are removed`() {
        val response = lyricResponse(
            """{"lrc":{"lyric":"[00:01.00]作词: 无\n[00:02.00]保留的歌词\n[00:03.00]作词: 有人"}}""",
        )

        val parsed = LyricsParser.parse(response)

        assertFalse(parsed.isInstrumental)
        assertEquals(listOf("保留的歌词", "作词: 有人"), parsed.lines.map { it.text })
    }

    @Test
    fun `lrc only responses parse without yrc`() {
        val response = lyricResponse(
            """{"lrc":{"lyric":"[00:10.00]只有 LRC"},"tlyric":{"lyric":"[00:10.00]LRC only"}}""",
        )

        val parsed = LyricsParser.parse(response)

        assertEquals(1, parsed.lines.size)
        assertNull(parsed.lines[0].words)
        assertEquals("只有 LRC", parsed.lines[0].text)
        assertEquals("LRC only", parsed.lines[0].translation)
    }

    @Test
    fun `missing lrc yields empty lyrics`() {
        val parsed = LyricsParser.parse(lyricResponse("""{"nolyric":true}"""))

        assertTrue(parsed.isEmpty)
        assertFalse(parsed.isInstrumental)
        assertNull(parsed.contributor)
        assertNull(parsed.activeIndex(position = 1.0))
    }
}
