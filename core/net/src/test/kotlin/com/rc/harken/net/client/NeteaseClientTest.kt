// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken.net.client

import java.io.FileNotFoundException
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Transport-layer tests through MockWebServer: URL/headers/form body shape,
 * cookie absorption, csrf injection and the eapi message format. The captured
 * `params` are decrypted back to prove the payload round-trips byte-exactly.
 */
class NeteaseClientTest {

    private lateinit var server: MockWebServer
    private lateinit var cookieStore: InMemoryCookieStore
    private lateinit var client: NeteaseClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        cookieStore = InMemoryCookieStore()
        client = NeteaseClient(cookieStore = cookieStore, baseUrl = base, eapiBaseUrl = base)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueue(body: String = "{}", cookie: String? = null) {
        val response = MockResponse().setResponseCode(200).setBody(body)
        if (cookie != null) response.addHeader("Set-Cookie", cookie)
        server.enqueue(response)
    }

    private fun take(): RecordedRequest =
        server.takeRequest(timeout = 5, unit = TimeUnit.SECONDS)!!

    private fun formFields(recorded: RecordedRequest): Map<String, String> =
        recorded.body.readUtf8().split("&").associate {
            val (key, value) = it.split("=", limit = 2)
            key to value
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

    private fun decryptWeapi(params: String): String {
        val decoded = percentDecode(params)
        val second = aesCbc(Base64.getDecoder().decode(decoded), SECRET_KEY, IV)
        return String(aesCbc(Base64.getDecoder().decode(String(second, Charsets.UTF_8)), PRESET_KEY, IV))
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

    private fun decryptEapi(params: String): String {
        val bytes = ByteArray(params.length / 2) { params.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return String(aesEcb(bytes, EAPI_KEY), Charsets.UTF_8)
    }

    @Test
    fun `weapi request shape matches iOS`() = runBlocking {
        enqueue("""{"code":200}""")

        val body = client.weapi("/login/qrcode/unikey", mapOf("type" to 1))

        assertEquals("""{"code":200}""", body)
        val recorded = take()
        assertEquals("POST", recorded.method)
        assertEquals("/weapi/login/qrcode/unikey", recorded.path)
        assertTrue(recorded.getHeader("User-Agent")!!.startsWith("Mozilla/5.0 (Macintosh"))
        assertEquals("https://music.163.com", recorded.getHeader("Referer"))
        assertEquals("application/x-www-form-urlencoded", recorded.getHeader("Content-Type"))
        val cookieHeader = recorded.getHeader("Cookie")!!
        assertTrue(cookieHeader.contains("os=pc"))
        assertTrue(cookieHeader.contains("appver=3.1.17"))

        val form = formFields(recorded)
        assertEquals(setOf("params", "encSecKey"), form.keys)
        // Base64 + / = must be percent-encoded in the form body.
        val rawParams = form.getValue("params")
        assertFalse(rawParams.any { it == '+' || it == '/' || it == '=' })
        assertEquals("""{"type":1,"csrf_token":""}""", decryptWeapi(rawParams))
    }

    @Test
    fun `csrf token is injected into body and query`() = runBlocking {
        cookieStore.update(mapOf("__csrf" to "abc123"))
        enqueue()

        client.weapi("/playlist/subscribe", mapOf("id" to 1L))

        val recorded = take()
        assertEquals("/weapi/playlist/subscribe?csrf_token=abc123", recorded.path)
        assertTrue(recorded.getHeader("Cookie")!!.contains("__csrf=abc123"))
        val payload = decryptWeapi(formFields(recorded).getValue("params"))
        assertTrue(payload.contains("\"csrf_token\":\"abc123\""))
        assertTrue(payload.contains("\"id\":1"))
    }

    @Test
    fun `set cookies are absorbed from responses`() = runBlocking {
        enqueue(cookie = "MUSIC_U=absorbed-token; Path=/; HttpOnly")
        enqueue(cookie = "__csrf=absorbed-csrf; Path=/")

        client.weapi("/w/nuser/account/get")
        client.weapi("/w/nuser/account/get")

        assertEquals("absorbed-token", client.cookie("MUSIC_U"))
        assertEquals("absorbed-csrf", client.cookie("__csrf"))
        assertTrue(client.isLoggedIn)
        assertEquals(setOf("MUSIC_U", "__csrf"), client.authenticationCookies().keys)
    }

    @Test
    fun `empty set cookies are ignored`() = runBlocking {
        enqueue(cookie = "MUSIC_U=; Path=/")
        client.weapi("/a")
        assertFalse(client.isLoggedIn)
    }

    @Test
    fun `eapi request carries the header payload and digest path`() = runBlocking {
        cookieStore.update(mapOf("MUSIC_U" to "eapi-token", "__csrf" to "eapi-csrf"))
        enqueue("""{"code":200,"data":[]}""")

        client.eapi("/song/enhance/player/url/v1", mapOf("ids" to "[347230]", "level" to "standard"))

        val recorded = take()
        assertEquals("POST", recorded.method)
        assertEquals("/eapi/song/enhance/player/url/v1", recorded.path)
        val cookieHeader = recorded.getHeader("Cookie")!!
        assertTrue(cookieHeader.contains("MUSIC_U=eapi-token"))

        val message = decryptEapi(formFields(recorded).getValue("params"))
        assertTrue(message.startsWith("/api/song/enhance/player/url/v1-36cd479b6b5-"))
        assertTrue(message.contains("\"ids\":\"[347230]\""))
        // The eapi header block mirrors the iOS client.
        assertTrue(message.contains("\"os\":\"pc\""))
        assertTrue(message.contains("\"appver\":\"3.1.17\""))
        assertTrue(message.contains("\"deviceId\":\"harken\""))
        assertTrue(message.contains("\"resolution\":\"1920x1080\""))
        assertTrue(message.contains("\"MUSIC_U\":\"eapi-token\""))
        assertTrue(message.contains("\"__csrf\":\"eapi-csrf\""))
        assertTrue(Regex("\"requestId\":\"[23]\\d{7}\"").containsMatchIn(message))
        assertTrue(Regex("\"buildver\":\\d{10}").containsMatchIn(message))
        assertTrue(Regex("-36cd479b6b5-[0-9a-f]{32}$").containsMatchIn(message))
    }

    @Test
    fun `refresh login absorbs the renewed token`() = runBlocking {
        cookieStore.update(mapOf("MUSIC_U" to "first"))
        enqueue(cookie = "MUSIC_U=renewed; Path=/")

        client.weapi("/login/token/refresh")

        assertEquals("renewed", client.cookie("MUSIC_U"))
        assertEquals("/weapi/login/token/refresh", take().path)
    }

    @Test
    fun `ingest cookie string parses double-semicolon joined raw cookies`() = runBlocking {
        enqueue()
        client.ingestCookieString("MUSIC_U=raw-token;; __csrf=raw-csrf ;; os=pc")

        assertEquals("raw-token", client.cookie("MUSIC_U"))
        assertEquals("raw-csrf", client.cookie("__csrf"))
        assertEquals("pc", client.cookie("os"))
    }

    @Test
    fun `payload values round-trip through the form encoding`() = runBlocking {
        enqueue()
        // A value with characters that must be percent-encoded in the form body.
        client.weapi("/search", mapOf("s" to "海阔天空+Beyond/星"))

        val payload = decryptWeapi(formFields(take()).getValue("params"))
        assertTrue(payload.contains("\"s\":\"海阔天空+Beyond/星\""))
    }

    private companion object {
        const val PRESET_KEY = "0CoJUm6Qyw8W8jud"
        const val IV = "0102030405060708"
        const val SECRET_KEY = "kumone2026abcDEF" // 协议常量，与 NeteaseCrypto 配对，勿改名
        const val EAPI_KEY = "e82ckenh8dichen8"
    }
}
