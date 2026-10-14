// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.net.model

import im.missuo.kumone.net.client.NeteaseJson
import java.io.FileNotFoundException
import java.util.Locale
import java.util.regex.Pattern
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decoding tests for the tolerant model layer: v3 vs legacy track shapes,
 * lenient numeric fields, `noCopyright`/`isCloud` presence semantics, the
 * `TrackPlayability` decision chain and the display formatters.
 */
class ModelDecodingTest {

    private val json: Json = NeteaseJson

    private fun fixture(name: String): String {
        val stream = javaClass.getResourceAsStream("/fixtures/$name")
            ?: throw FileNotFoundException("missing test resource: fixtures/$name")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun track(rawJson: String): Track = json.decodeFromString(rawJson)

    @Test
    fun `decodes v3 track shape`() {
        val response = json.decodeFromString<SongDetailResponse>(fixture("track-v3-shape.json"))
        val track = response.songs.single()
        assertEquals(347230L, track.id)
        assertEquals("海阔天空", track.name)
        assertEquals(listOf("Beyond", "黄家驹"), track.artists.map { it.name })
        assertEquals(34512L, track.album.id)
        assertEquals("http://p1.music.126.net/abc=example.jpg", track.album.picUrl)
        assertEquals(326000L, track.durationMS)
        assertEquals(listOf("海闊天空"), track.alias)
        assertEquals(listOf("Boundless Oceans, Vast Skies"), track.transNames)
        assertEquals("Beyond / 黄家驹", track.artistNames)
        assertEquals("Boundless Oceans, Vast Skies", track.subtitle)
        assertEquals(123, track.mvID)
        assertEquals(1, track.trackNo)
        assertEquals("1", track.cd)
        assertFalse(track.noCopyright)
        assertFalse(track.isCloud)
        assertNull(track.embeddedPrivilege)
        assertEquals(326.0, track.durationSeconds, 1e-9)

        val privilege = response.privileges.single()
        assertEquals(320000, privilege.pl)
        assertEquals(TrackPlayability.PLAYABLE, track.playability(privilege, isLoggedIn = false, vipType = 0))
    }

    @Test
    fun `decodes legacy track shape with lenient numbers`() {
        val response = json.decodeFromString<SongDetailResponse>(fixture("track-legacy-shape.json"))
        val (vip, noCopyright, delisted) = response.songs

        // Numeric-string ids and the legacy `artists`/`album`/`duration`/`alias` keys.
        assertEquals(347230L, vip.id)
        assertEquals(listOf("Beyond"), vip.artists.map { it.name })
        assertEquals(34512L, vip.album.id)
        assertEquals(326000L, vip.durationMS)
        assertEquals(listOf("海闊天空"), vip.alias)

        // `noCopyrightRcmd: null` ⇒ not no-copyright; `pc` present ⇒ cloud track.
        assertFalse(vip.noCopyright)
        assertTrue(vip.isCloud)
        assertEquals(TrackPlayability.VIP_ONLY, vip.playability(null, isLoggedIn = true, vipType = 0))
        assertEquals(TrackPlayability.PLAYABLE, vip.playability(null, isLoggedIn = true, vipType = 110))

        // `noCopyrightRcmd: {}` ⇒ no-copyright.
        assertTrue(noCopyright.noCopyright)
        assertFalse(noCopyright.isCloud)
        assertEquals(TrackPlayability.NO_COPYRIGHT, noCopyright.playability(null, isLoggedIn = false, vipType = 0))

        // `privilege.st < 0` only delists when logged in.
        assertEquals(TrackPlayability.DELISTED, delisted.playability(null, isLoggedIn = true, vipType = 0))
        assertEquals(TrackPlayability.PLAYABLE, delisted.playability(null, isLoggedIn = false, vipType = 0))
    }

    @Test
    fun `playability decision chain matches YesPlayMusic`() {
        val plain = track("""{"id":1,"fee":0}""")
        assertEquals(TrackPlayability.PLAYABLE, plain.playability(null, false, 0))

        val cloudCs = track("""{"id":2,"fee":0}""")
        val csPrivilege = TrackPrivilege(id = 2, fee = 0, pl = 0, st = 0, cs = true, maxbr = 0)
        assertEquals(TrackPlayability.PLAYABLE, cloudCs.playability(csPrivilege, isLoggedIn = true, vipType = 0))
        assertEquals(TrackPlayability.PLAYABLE, cloudCs.playability(csPrivilege, isLoggedIn = false, vipType = 0))

        val vipTrack = track("""{"id":3,"fee":1}""")
        assertEquals(TrackPlayability.VIP_ONLY, vipTrack.playability(null, isLoggedIn = false, vipType = 0))

        val paid = track("""{"id":4,"fee":4}""")
        assertEquals(TrackPlayability.PAID_ALBUM, paid.playability(null, isLoggedIn = true, vipType = 0))

        // pl > 0 wins over everything else.
        val plWins = track("""{"id":5,"fee":4,"noCopyrightRcmd":{}}""")
        val plPrivilege = TrackPrivilege(id = 5, fee = 4, pl = 1, st = -1, cs = false, maxbr = 0)
        assertEquals(TrackPlayability.PLAYABLE, plWins.playability(plPrivilege, isLoggedIn = true, vipType = 0))
    }

    @Test
    fun `playlist summary tolerates playCount shapes`() {
        val response = json.decodeFromString<UserPlaylistsResponse>(fixture("playlist-summary-shape.json"))
        val (radar, liked) = response.playlist

        assertEquals(3136952023L, radar.id)
        assertEquals("http://p1.music.126.net/radar=example.jpg", radar.coverURL)
        assertEquals(19200000, radar.playCount)
        assertEquals(40, radar.trackCount)
        assertEquals("missuo", radar.creator?.nickname)
        assertTrue(radar.subscribed)

        assertEquals(12345678, liked.playCount)
        assertEquals("http://p2.music.126.net/liked=example.jpg", liked.coverURL)
        assertTrue(liked.isLikedSongsList)
        assertFalse(radar.isLikedSongsList)
        assertTrue(response.more == true)
    }

    @Test
    fun `cloud songs tolerate nested and flat shapes`() {
        val response = json.decodeFromString<CloudResponse>(fixture("cloud-songs-shape.json"))
        val (flat, nested) = response.data

        assertEquals(100L, flat.songId)
        assertEquals(100L, flat.id)
        assertEquals("云盘歌曲", flat.songName)
        assertEquals("Beyond", flat.artist)
        assertEquals(12345678L, flat.fileSize)
        assertEquals(326000L, flat.simpleSong?.durationMS)

        assertEquals(200L, nested.songId)
        assertEquals("旧版云盘歌曲", nested.songName)
        assertEquals("someone", nested.artist)
        assertEquals(999L, nested.fileSize)

        assertEquals(1073741824L, response.size)
        assertEquals(2147483648L, response.maxSize)
        assertTrue(response.hasMore == true)
    }

    @Test
    fun `formatters render counts durations and image urls`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("zh-CN"))
            assertEquals("1234.6万", Formatters.playCount(12345678))
            assertEquals("1.2亿", Formatters.playCount(123456789))
            assertEquals("952", Formatters.playCount(952))

            Locale.setDefault(Locale.US)
            assertEquals("12.3M", Formatters.playCount(12345678))
            assertEquals("123.5M", Formatters.playCount(123456789))
            assertEquals("123.5K", Formatters.playCount(123456))
            assertEquals("952", Formatters.playCount(952))
        } finally {
            Locale.setDefault(previous)
        }

        assertEquals("3:59", Formatters.duration(239.0))
        assertEquals("1:00", Formatters.duration(60.0))
        assertEquals("0:05", Formatters.duration(4.6))
        assertEquals("1 小时 1 分钟", Formatters.longDuration(3700.0))
        assertEquals("2 分钟", Formatters.longDuration(125.0))

        assertEquals("", Formatters.date(0))
        assertTrue(
            Pattern.matches("\\d{4}-\\d{2}-\\d{2}", Formatters.date(1_704_110_400_000L)),
        )

        assertEquals(
            "https://p1.music.126.net/x.jpg?param=140y140",
            Formatters.resizedImageURL("http://p1.music.126.net/x.jpg", 140),
        )
        assertEquals(
            "https://p1.music.126.net/x.jpg?a=1&param=140y140",
            Formatters.resizedImageURL("https://p1.music.126.net/x.jpg?a=1", 140),
        )
        assertNull(Formatters.resizedImageURL(null, 140))
        assertNull(Formatters.resizedImageURL("", 140))
    }
}
