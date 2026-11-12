// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken.net.api

import com.rc.harken.net.client.InMemoryCookieStore
import com.rc.harken.net.client.NeteaseClient
import com.rc.harken.net.model.TrackPlayability
import java.io.FileNotFoundException
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Endpoint tests driven by recorded fixtures through MockWebServer: URL shape,
 * headers, encrypted form bodies (verified by decrypting them back), cookie
 * absorption and business-error mapping.
 */
class NeteaseApiTest {

    private lateinit var server: MockWebServer
    private lateinit var cookieStore: InMemoryCookieStore
    private lateinit var client: NeteaseClient
    private lateinit var api: NeteaseApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        cookieStore = InMemoryCookieStore()
        client = NeteaseClient(
            cookieStore = cookieStore,
            baseUrl = base,
            eapiBaseUrl = base,
        )
        api = NeteaseApi(client)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun fixture(name: String): String {
        val stream = javaClass.getResourceAsStream("/fixtures/$name")
            ?: throw FileNotFoundException("missing test resource: fixtures/$name")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun enqueue(body: String, code: Int = 200, cookie: String? = null) {
        val response = MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
        if (cookie != null) response.addHeader("Set-Cookie", cookie)
        server.enqueue(response)
    }

    private fun takeRequest(): RecordedRequest =
        server.takeRequest(5, TimeUnit.SECONDS)!!

    private fun formFields(recorded: RecordedRequest): Map<String, String> {
        val body = recorded.body.readUtf8()
        return body.split("&").associate {
            val (key, value) = it.split("=", limit = 2)
            key to value
        }
    }

    /** Reverses `NeteaseCrypto.weapi` to recover the original JSON payload. */
    private fun decryptWeapiPayload(params: String): String {
        val second = aesCbc(base64Decode(percentDecode(params)), WEAPI_SECRET_KEY, WEAPI_IV)
        val firstBase64 = String(second, Charsets.UTF_8)
        val first = aesCbc(base64Decode(firstBase64), WEAPI_PRESET_KEY, WEAPI_IV)
        return String(first, Charsets.UTF_8)
    }

    /** Reverses the form-body percent-encoding (`A-Za-z0-9-._~` pass through). */
    private fun percentDecode(value: String): String {
        val bytes = java.io.ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character == '%' && index + 2 < value.length) {
                bytes.write(value.substring(index + 1, index + 3).toInt(16).toInt())
                index += 3
            } else {
                for (byte in character.toString().toByteArray(Charsets.UTF_8)) {
                    bytes.write(byte.toInt())
                }
                index += 1
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    /** Reverses `NeteaseCrypto.eapi` to recover `"{apiPath}-36cd479b6b5-{payload}-36cd479b6b5-{md5}"`. */
    private fun decryptEapiMessage(params: String): String {
        val bytes = ByteArray(params.length / 2) { index ->
            params.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return String(aesEcb(bytes, EAPI_KEY), Charsets.UTF_8)
    }

    private fun aesCbc(data: ByteArray, key: String, iv: String): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
                IvParameterSpec(iv.toByteArray(Charsets.UTF_8)),
            )
            doFinal(data)
        }

    private fun aesEcb(data: ByteArray, key: String): ByteArray =
        Cipher.getInstance("AES/ECB/PKCS5Padding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"))
            doFinal(data)
        }

    private fun base64Decode(value: String): ByteArray = Base64.getDecoder().decode(value)

    // MARK: - Auth

    @Test
    fun `qrKey posts to weapi and parses unikey`() = runBlocking {
        enqueue(fixture("qr-key.json"))

        val unikey = api.qrKey()

        assertEquals("9f8e7d6c-5b4a-3210-fedc-ba9876543210", unikey)
        val recorded = takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/weapi/login/qrcode/unikey", recorded.path)
        assertEquals("application/x-www-form-urlencoded", recorded.getHeader("Content-Type"))
        assertTrue(recorded.getHeader("User-Agent")!!.contains("Macintosh"))
        assertEquals("https://music.163.com", recorded.getHeader("Referer"))
        assertTrue(recorded.getHeader("Cookie")!!.contains("os=pc"))
        assertTrue(recorded.getHeader("Cookie")!!.contains("appver=3.1.17"))

        val form = formFields(recorded)
        assertEquals(setOf("params", "encSecKey"), form.keys)
        assertTrue(form.getValue("encSecKey").matches(Regex("[0-9a-f]{256}")))
        assertEquals("""{"type":1,"csrf_token":""}""", decryptWeapiPayload(form.getValue("params")))
    }

    @Test
    fun `qrCheck parses waiting and success states`() = runBlocking {
        enqueue(fixture("qr-check-801.json"))
        val waiting = api.qrCheck("unikey-1")
        assertEquals(801, waiting.code)
        assertEquals("等待扫码", waiting.message)
        assertNull(waiting.nickname)

        enqueue(fixture("qr-check-803.json"))
        val success = api.qrCheck("unikey-1")
        assertEquals(803, success.code)
        assertEquals("harken_tester", success.nickname)
        assertTrue(success.avatarUrl!!.contains("avatar"))

        // 803 never goes through the business-code check.
        val paths = mutableListOf<String>()
        repeat(2) { paths.add(takeRequest().path!!) }
        assertTrue(paths.all { it == "/weapi/login/qrcode/client/login" })
    }

    @Test
    fun `login cellphone requires the auth cookie`() = runBlocking {
        // 200 but no Set-Cookie: the local jar stays empty ⇒ business error.
        enqueue("""{"code":200}""")
        val error = runCatching { api.loginCellphone("13800000000", "1234") }.exceptionOrNull()
        assertTrue(error is NeteaseApiError.Business)
        assertEquals(-1, (error as NeteaseApiError.Business).code)

        // With Set-Cookie the login completes and the jar absorbs MUSIC_U.
        enqueue("""{"code":200}""", cookie = "MUSIC_U=cellphone-token; Path=/; HttpOnly")
        api.loginCellphone("13800000000", "1234")
        assertTrue(client.isLoggedIn)
        assertEquals("cellphone-token", client.cookie("MUSIC_U"))
    }

    @Test
    fun `business errors map to typed failures`() = runBlocking {
        enqueue("""{"code":301}""")
        assertThrowsAsync<NeteaseApiError.NeedLogin> { api.userAccount() }

        enqueue("""{"code":400,"message":"参数错误"}""")
        val error = assertThrowsAsync<NeteaseApiError.Business> { api.userAccount() }
        assertEquals(400, error.code)
        assertEquals("参数错误", error.message)
        assertEquals("参数错误", error.userMessage())
    }

    // MARK: - SMS captcha contract

    /**
     * 契约黄金断言：官方 `sms/captcha/sent` 只收 `cellphone` + `ctcode`。
     *
     * 依据反编译产物 `tf0/i.java:766-784`：旧实现误用 `phone` 并多传 `secrete`，
     * 导致服务端缺必填参数返回 400「参数错误」。这里解密 weapi 的 `params`，
     * 逐字段锁死请求体，避免「改回 phone」在既有 176 条单测下静默通过。
     */
    @Test
    fun `sendSMSCode posts cellphone and ctcode to the sms captcha endpoint`() = runBlocking {
        enqueue("""{"code":200}""")

        api.sendSMSCode("13800000000")

        assertEquals(1, server.requestCount)
        val recorded = takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/weapi/sms/captcha/sent", recorded.path)
        assertEquals("application/x-www-form-urlencoded", recorded.getHeader("Content-Type"))

        val form = formFields(recorded)
        assertEquals(setOf("params", "encSecKey"), form.keys)
        assertTrue(form.getValue("encSecKey").matches(Regex("[0-9a-f]{256}")))

        val payload = decryptWeapiPayload(form.getValue("params"))
        // 与 qrKey 的黄金断言同风格：解密后的 JSON 恰为业务参数 + 注入的 csrf_token。
        assertEquals(
            """{"cellphone":"13800000000","ctcode":"86","csrf_token":""}""",
            payload,
        )
        val json = Json.parseToJsonElement(payload) as JsonObject
        assertEquals(setOf("cellphone", "ctcode", "csrf_token"), json.keys)
        assertEquals("13800000000", (json.getValue("cellphone") as JsonPrimitive).content)
        assertEquals("86", (json.getValue("ctcode") as JsonPrimitive).content)
        assertEquals("", (json.getValue("csrf_token") as JsonPrimitive).content)

        // 旧实现遗留字段：字段名、数量、值任意一处回归都必须红灯。
        assertFalse(json.containsKey("phone"))
        assertFalse(json.containsKey("secrete"))
        assertFalse(json.containsKey("music_middleuser_pclogin"))
        assertFalse(payload.contains("\"phone\""))
        assertFalse(payload.contains("\"secrete\""))
        assertFalse(payload.contains("music_middleuser_pclogin"))
    }

    @Test
    fun `sendSMSCode forwards a non default ctcode unchanged`() = runBlocking {
        enqueue("""{"code":200}""")

        api.sendSMSCode("13800000000", "852")

        assertEquals(1, server.requestCount)
        val recorded = takeRequest()
        assertEquals("/weapi/sms/captcha/sent", recorded.path)
        val form = formFields(recorded)
        assertEquals(setOf("params", "encSecKey"), form.keys)

        val payload = decryptWeapiPayload(form.getValue("params"))
        assertEquals(
            """{"cellphone":"13800000000","ctcode":"852","csrf_token":""}""",
            payload,
        )
        val json = Json.parseToJsonElement(payload) as JsonObject
        assertEquals(setOf("cellphone", "ctcode", "csrf_token"), json.keys)
        assertEquals("13800000000", (json.getValue("cellphone") as JsonPrimitive).content)
        assertEquals("852", (json.getValue("ctcode") as JsonPrimitive).content)
        assertEquals("", (json.getValue("csrf_token") as JsonPrimitive).content)
        assertFalse(json.containsKey("phone"))
        assertFalse(json.containsKey("secrete"))
        assertFalse(json.containsKey("music_middleuser_pclogin"))
    }

    @Test
    fun `sendSMSCode maps 400 parameter error to a typed business error`() = runBlocking {
        // 旧 bug 的服务端响应：参数名写错时 sms/captcha/sent 返回 400。
        enqueue("""{"code":400,"message":"参数错误"}""")

        val error = assertThrowsAsync<NeteaseApiError.Business> {
            api.sendSMSCode("13800000000")
        }
        assertEquals(400, error.code)
        assertEquals("参数错误", error.message)
        assertEquals("参数错误", error.userMessage())

        // 失败也必须确实是打到了本端点的合规请求，而非别的路径/参数。
        assertEquals(1, server.requestCount)
        val recorded = takeRequest()
        assertEquals("/weapi/sms/captcha/sent", recorded.path)
        val payload = decryptWeapiPayload(formFields(recorded).getValue("params"))
        assertEquals(
            """{"cellphone":"13800000000","ctcode":"86","csrf_token":""}""",
            payload,
        )
    }

    @Test
    fun `decoding failures map to Decoding`() = runBlocking {
        // A numeric-string id is tolerated by the lenient serializer.
        enqueue("""{"code":200,"profile":{"userId":"not-a-number","nickname":"harken_tester"}}""")
        assertEquals("harken_tester", api.userAccount()?.nickname)

        // A number where a string belongs is a genuine decoding failure.
        enqueue("""{"code":200,"profile":{"userId":1,"nickname":123}}""")
        val error = assertThrowsAsync<NeteaseApiError.Decoding> { api.userAccount() }
        assertEquals("数据加载失败，请稍后重试", error.userMessage())

        enqueue("""{"code":200,"profile":null}""")
        assertNull(api.userAccount())
    }

    // MARK: - Playlists / tracks / search / toplists

    @Test
    fun `playlistDetail parses tracks ids and privileges`() = runBlocking {
        enqueue(fixture("playlist-detail.json"))

        val response = api.playlistDetail(3136952023L)

        assertEquals(3136952023L, response.playlist.id)
        assertEquals("海阔天空 Beyond 精选", response.playlist.name)
        assertEquals(1.92E7.toInt(), response.playlist.playCount)
        assertEquals(2, response.playlist.trackIds.size)
        assertEquals(listOf("海阔天空", "光辉岁月"), response.playlist.tracks.map { it.name })
        assertEquals(320000, response.privileges.first().pl)
        assertEquals(
            TrackPlayability.PLAYABLE,
            response.playlist.tracks.first().playability(response.privileges.first(), false, 0),
        )
        assertEquals(
            TrackPlayability.VIP_ONLY,
            response.playlist.tracks.last().playability(response.privileges.last(), false, 0),
        )

        val recorded = takeRequest()
        assertEquals("/weapi/v6/playlist/detail", recorded.path)
        val payload = decryptWeapiPayload(formFields(recorded).getValue("params"))
        assertTrue(payload.contains("\"n\":100000"))
        assertTrue(payload.contains("\"s\":8"))
        assertTrue(payload.contains("\"id\":3136952023"))
    }

    @Test
    fun `songURL posts to eapi with the digest path`() = runBlocking {
        enqueue(fixture("song-url.json"))

        val data = api.songURL(listOf(347230L), level = "standard")

        assertEquals(2, data.size)
        assertEquals("http://m8.music.126.net/20241013/example-song.mp3", data[0].url)
        assertEquals(128000, data[0].br)
        assertEquals(326000L, data[0].time)
        assertNull(data[1].url)

        val recorded = takeRequest()
        assertEquals("/eapi/song/enhance/player/url/v1", recorded.path)
        val message = decryptEapiMessage(formFields(recorded).getValue("params"))
        assertTrue(message.startsWith("/api/song/enhance/player/url/v1-36cd479b6b5-"))
        assertTrue(message.contains("\"ids\":\"[347230]\""))
        assertTrue(message.contains("\"level\":\"standard\""))
        assertTrue(message.contains("\"encodeType\":\"flac\""))
        assertTrue(Regex("-36cd479b6b5-[0-9a-f]{32}$").containsMatchIn(message))
    }

    @Test
    fun `albumDynamic posts to eapi and parses subscription`() = runBlocking {
        enqueue("""{"code":200,"isSub":true,"subCount":42}""")

        val result = api.albumDynamic(777L)

        assertTrue(result.isSub == true)
        assertEquals(42, result.subCount)

        val recorded = takeRequest()
        assertEquals("/eapi/album/detail/dynamic", recorded.path)
        val message = decryptEapiMessage(formFields(recorded).getValue("params"))
        assertTrue(message.startsWith("/api/album/detail/dynamic-36cd479b6b5-"))
        assertTrue(Regex("\"id\"\\s*:\\s*777").containsMatchIn(message))
        assertTrue(Regex("-36cd479b6b5-[0-9a-f]{32}$").containsMatchIn(message))
    }

    @Test
    fun `search parses cloudsearch sections`() = runBlocking {
        enqueue(fixture("search-cloud.json"))

        val result = api.search("海阔天空", type = 1)

        assertEquals("海阔天空", result.songs?.single()?.name)
        assertEquals(TrackPlayability.VIP_ONLY, result.songs?.single()?.playability(null, false, 0))
        assertEquals("Beyond", result.albums?.single()?.artistName)
        assertEquals("Beyond", result.artists?.single()?.name)
        assertEquals(1.92E7.toInt(), result.playlists?.single()?.playCount)
        assertEquals(1, result.songCount)

        val recorded = takeRequest()
        assertEquals("/eapi/cloudsearch/pc", recorded.path)
        val message = decryptEapiMessage(formFields(recorded).getValue("params"))
        assertTrue(message.startsWith("/api/cloudsearch/pc-36cd479b6b5-"))
        assertTrue(message.contains("\"s\":\"海阔天空\""))
        assertTrue(message.contains("\"type\":1"))
    }

    @Test
    fun `toplists parses eapi list`() = runBlocking {
        enqueue(fixture("toplists.json"))

        val lists = api.toplists()

        assertEquals(2, lists.size)
        assertEquals(19723756L, lists[0].id)
        assertEquals("飙升榜", lists[0].name)
        assertEquals("海阔天空", lists[0].tracks.single().first)
        assertEquals("Beyond", lists[0].tracks.single().second)
        assertEquals(1.92E7.toInt(), lists[0].playCount)

        val recorded = takeRequest()
        assertEquals("/eapi/toplist", recorded.path)
    }

    @Test
    fun `songDetails sends the c array and parses songs`() = runBlocking {
        enqueue(fixture("track-v3-shape.json"))

        val response = api.songDetails(listOf(347230L))

        assertEquals(1, response.songs.size)
        val recorded = takeRequest()
        val payload = decryptWeapiPayload(formFields(recorded).getValue("params"))
        assertTrue(payload.contains("\"c\":\"[{\\\"id\\\":347230}]\""))

        // Empty ids never hit the network.
        val empty = api.songDetails(emptyList())
        assertTrue(empty.songs.isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `playlistTracks retries code 512 with doubled ids`() = runBlocking {
        enqueue("""{"code":512,"message":"歌单存在重复歌曲"}""")
        enqueue("""{"code":200}""")

        api.playlistTracks("add", playlistID = 12345L, trackIDs = listOf(1L, 2L))

        val first = takeRequest()
        val second = takeRequest()
        assertTrue(first.path!!.startsWith("/weapi/playlist/manipulate/tracks"))
        assertTrue(second.path!!.startsWith("/weapi/playlist/manipulate/tracks"))
        val firstPayload = decryptWeapiPayload(formFields(first).getValue("params"))
        val secondPayload = decryptWeapiPayload(formFields(second).getValue("params"))
        assertTrue(firstPayload.contains("\"trackIds\":\"[1,2]\""))
        assertTrue(secondPayload.contains("\"trackIds\":\"[1,2,1,2]\""))
        assertTrue(firstPayload.contains("\"pid\":12345"))
    }

    @Test
    fun `playlistTracks surfaces other business codes`() = runBlocking {
        enqueue("""{"code":400,"message":"Bad request"}""")
        val error = assertThrowsAsync<NeteaseApiError.Business> {
            api.playlistTracks("add", 1L, listOf(2L))
        }
        assertEquals(400, error.code)
        assertEquals("Bad request", error.message)
    }

    @Test
    fun `lyric prefers v1 and falls back when empty`() = runBlocking {
        enqueue("""{"code":200,"lrc":{"lyric":"[00:01.00]海阔天空"}}""")
        val v1 = api.lyric(347230L)
        assertEquals("[00:01.00]海阔天空", v1.lrc?.lyric)
        assertEquals(1, server.requestCount)

        enqueue("""{"code":200,"lrc":{"lyric":""},"yrc":{"lyric":""}}""")
        enqueue("""{"code":200,"lrc":{"lyric":"[00:02.00]光辉岁月"},"tlyric":{"lyric":"[00:02.00]Glorious Years"}}""")
        val fallback = api.lyric(347231L)
        assertEquals("[00:02.00]光辉岁月", fallback.lrc?.lyric)
        assertEquals(3, server.requestCount)
        // v1 first, the empty v1 retry, then the classic fallback.
        assertEquals("/weapi/song/lyric/v1", takeRequest().path)
        assertEquals("/weapi/song/lyric/v1", takeRequest().path)
        assertEquals("/weapi/song/lyric", takeRequest().path)
    }

    @Test
    fun `http errors map to Http`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(502).setBody("bad gateway"))
        val error = assertThrowsAsync<NeteaseApiError.Http> { api.qrKey() }
        assertEquals(502, error.status)
        assertEquals("网络错误 (502)", error.userMessage())
    }

    @Test
    fun `logout sends detached cookies and clears the jar`() = runBlocking {
        cookieStore.update(mapOf("MUSIC_U" to "token-1", "__csrf" to "csrf-1", "other" to "keep"))
        enqueue("""{"code":200}""")

        api.logout()

        assertFalse(client.isLoggedIn)
        assertNull(client.cookie("MUSIC_U"))
        assertEquals("keep", client.cookie("other"))
        val recorded = takeRequest()
        // The __csrf cookie is injected into the query as on iOS.
        assertEquals("/weapi/logout?csrf_token=csrf-1", recorded.path)
        val cookieHeader = recorded.getHeader("Cookie")!!
        assertTrue(cookieHeader.contains("MUSIC_U=token-1"))
        assertTrue(cookieHeader.contains("__csrf=csrf-1"))
    }

    @Test
    fun `personal fm and intelligence list parse tracks`() = runBlocking {
        enqueue(
            """{"code":200,"data":[{"id":1,"name":"FM 歌曲","ar":[{"id":9,"name":"歌手"}],"al":{"id":8,"name":"专辑"},"dt":100000}]}""",
        )
        val fm = api.personalFM()
        assertEquals("FM 歌曲", fm.single().name)

        enqueue(
            """{"code":200,"data":[{"id":1,"songInfo":{"id":5,"name":"心动歌曲","ar":[{"id":9,"name":"歌手"}],"al":{"id":8,"name":"专辑"},"dt":100000}}]}""",
        )
        val intelligence = api.intelligenceList(songID = 1L, playlistID = 2L)
        assertEquals("心动歌曲", intelligence.single().name)
    }

    private suspend inline fun <reified T : Throwable> assertThrowsAsync(crossinline block: suspend () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            if (e is CancellationException) throw e
            fail("expected ${T::class.simpleName} but got $e")
        }
        fail("expected ${T::class.simpleName} but nothing was thrown")
        error("unreachable")
    }

    private companion object {
        const val WEAPI_PRESET_KEY = "0CoJUm6Qyw8W8jud"
        const val WEAPI_IV = "0102030405060708"
        // 与 NeteaseCrypto.WEAPI_SECRET_KEY 保持一致（secretKey 随品牌改为 harken2026abcDEF）。
        const val WEAPI_SECRET_KEY = "harken2026abcDEF"
        const val EAPI_KEY = "e82ckenh8dichen8"
    }
}
