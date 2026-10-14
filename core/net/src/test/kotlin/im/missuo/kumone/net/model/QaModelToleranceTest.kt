// QA-authored tolerant-decoding tests: NetEase returns numeric fields as
// either JSON numbers or strings depending on the endpoint, so every model
// must survive both shapes plus missing/mistyped fields.

package im.missuo.kumone.net.model

import im.missuo.kumone.net.client.NeteaseJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `size`-style and other numeric fields must decode from both JSON shapes. */
class QaModelToleranceTest {

    private inline fun <reified T> decode(body: String): T =
        NeteaseJson.decodeFromString<T>(body)

    // ---- numeric field as a JSON number and as a JSON string ------------

    @Test
    fun `playlist summary decodes playCount from a number`() {
        val playlist = decode<PlaylistSummary>("""{"id":1,"playCount":12345}""")
        assertEquals(12345, playlist.playCount)
    }

    @Test
    fun `playlist summary decodes playCount from a numeric string`() {
        val playlist = decode<PlaylistSummary>("""{"id":1,"playCount":"12345"}""")
        assertEquals(12345, playlist.playCount)
    }

    @Test
    fun `playlist summary decodes trackCount from a numeric string`() {
        val playlist = decode<PlaylistSummary>("""{"id":1,"trackCount":"88"}""")
        assertEquals(88, playlist.trackCount)
    }

    @Test
    fun `playlist summary decodes id from a numeric string`() {
        val playlist = decode<PlaylistSummary>("""{"id":"3778678"}""")
        assertEquals(3778678L, playlist.id)
    }

    @Test
    fun `playlist summary decodes specialType from a numeric string`() {
        val playlist = decode<PlaylistSummary>("""{"id":1,"specialType":"5"}""")
        assertEquals(5, playlist.specialType)
        assertTrue("specialType 5 is the 我喜欢的音乐 list", playlist.isLikedSongsList)
    }

    @Test
    fun `track decodes id and duration from numeric strings`() {
        val track = decode<Track>("""{"id":"186016","dt":"240000"}""")
        assertEquals(186016L, track.id)
        assertEquals(240000L, track.durationMS)
    }

    @Test
    fun `track decodes fee from a numeric string`() {
        val track = decode<Track>("""{"id":1,"fee":"1"}""")
        assertEquals(1, track.fee)
    }

    @Test
    fun `privilege decodes from numeric strings`() {
        val track = decode<Track>("""{"id":1,"privilege":{"id":"1","fee":"4","pl":"1"}}""")
        assertEquals(4, track.embeddedPrivilege?.fee)
    }

    // ---- missing fields fall back to defaults ---------------------------

    @Test
    fun `a playlist with only an id decodes`() {
        val playlist = decode<PlaylistSummary>("""{"id":42}""")
        assertEquals(42L, playlist.id)
        assertEquals("", playlist.name)
        assertEquals(0, playlist.playCount)
        assertNull(playlist.creator)
        assertFalse(playlist.isLikedSongsList)
    }

    @Test
    fun `a track with only an id decodes`() {
        val track = decode<Track>("""{"id":7}""")
        assertEquals(7L, track.id)
        assertEquals(0L, track.durationMS)
        assertTrue(track.artists.isEmpty())
    }

    // ---- mistyped / hostile values --------------------------------------

    @Test
    fun `a non-numeric numeric field degrades to the default`() {
        val playlist = decode<PlaylistSummary>("""{"id":1,"playCount":"not-a-number"}""")
        assertEquals(0, playlist.playCount)
    }

    @Test
    fun `an empty numeric string degrades to the default`() {
        val playlist = decode<PlaylistSummary>("""{"id":1,"playCount":""}""")
        assertEquals(0, playlist.playCount)
    }

    @Test
    fun `a null numeric field degrades to the default`() {
        val playlist = decode<PlaylistSummary>("""{"id":1,"playCount":null}""")
        assertEquals(0, playlist.playCount)
    }

    @Test
    fun `unknown keys are ignored`() {
        val playlist = decode<PlaylistSummary>(
            """{"id":1,"totallyNewField":{"nested":[1,2,3]},"playCount":5}""",
        )
        assertEquals(5, playlist.playCount)
    }

    @Test
    fun `negative playCount is preserved rather than clamped`() {
        val playlist = decode<PlaylistSummary>("""{"id":1,"playCount":"-1"}""")
        assertEquals(-1, playlist.playCount)
    }

    // ---- creator / ownership --------------------------------------------

    @Test
    fun `creator userId decodes from a numeric string`() {
        val playlist = decode<PlaylistSummary>(
            """{"id":1,"creator":{"userId":"555","nickname":"me"}}""",
        )
        assertEquals(555L, playlist.creator?.userId)
        assertEquals("me", playlist.creator?.nickname)
    }

    @Test
    fun `a playlist without a creator is not owned by anyone`() {
        val playlist = decode<PlaylistSummary>("""{"id":1}""")
        assertNull(playlist.creator?.userId)
    }

    // ---- song url --------------------------------------------------------

    @Test
    fun `song url decodes from both numeric shapes for br`() {
        val number = decode<SongURLData>("""{"id":1,"url":"https://a","br":128000}""")
        val string = decode<SongURLData>("""{"id":1,"url":"https://a","br":"128000"}""")
        assertEquals(number.br, string.br)
    }

    @Test
    fun `song url with a null url decodes to null`() {
        val data = decode<SongURLData>("""{"id":1,"url":null,"br":128000}""")
        assertNull(data.url)
    }

    @Test
    fun `song url freeTrialInfo is null when absent`() {
        val data = decode<SongURLData>("""{"id":1,"url":"https://a"}""")
        assertNull("absence means not a trial", data.freeTrialInfo)
    }

    @Test
    fun `song url freeTrialInfo is detected when present`() {
        val data = decode<SongURLData>(
            """{"id":1,"url":"https://a","freeTrialInfo":{"start":0,"end":30}}""",
        )
        assertTrue("a present trial marker must be reported", data.freeTrialInfo != null)
    }

    // ---- user profile ----------------------------------------------------

    @Test
    fun `user profile decodes a numeric-string userId and vipType`() {
        val profile = decode<UserProfile>("""{"userId":"12345","nickname":"me","vipType":"7"}""")
        assertEquals(12345L, profile.userId)
        assertEquals(7, profile.vipType)
    }

    @Test
    fun `user profile tolerates a missing nickname`() {
        val profile = decode<UserProfile>("""{"userId":"1"}""")
        assertEquals("", profile.nickname)
    }

    // ---- size field (the shape the brief calls out) ----------------------

    @Test
    fun `song url size decodes from a JSON number`() {
        val data = decode<SongURLData>("""{"id":1,"url":"https://a","size":12345678}""")
        assertEquals(12345678L, data.size)
    }

    @Test
    fun `song url size decodes from a numeric string`() {
        val data = decode<SongURLData>("""{"id":1,"url":"https://a","size":"12345678"}""")
        assertEquals(12345678L, data.size)
    }

    @Test
    fun `song url size defaults to zero when absent`() {
        val data = decode<SongURLData>("""{"id":1,"url":"https://a"}""")
        assertEquals(0L, data.size)
    }

    @Test
    fun `song url size degrades to zero for junk`() {
        val data = decode<SongURLData>("""{"id":1,"size":"huge"}""")
        assertEquals(0L, data.size)
    }

    /** A zero/negative size must not crash the decoder (caller guards on it). */
    @Test
    fun `song url size accepts a negative value without throwing`() {
        val data = decode<SongURLData>("""{"id":1,"size":"-5"}""")
        assertEquals(-5L, data.size)
    }
}