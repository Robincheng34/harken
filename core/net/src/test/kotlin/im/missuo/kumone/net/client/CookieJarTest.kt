// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.net.client

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Cookie-jar tests: `;;` raw-string parsing, merge/clear semantics, the auth
 * cookie filter and the epoch rollback guard (a stale Set-Cookie from before a
 * token renewal must not roll the renewed credentials back).
 */
class CookieJarTest {

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

    // MARK: - ;; parsing

    @Test
    fun `ingest cookie string parses attributes and whitespace`() {
        client.ingestCookieString(
            "MUSIC_U=token-1; Path=/; HttpOnly;; __csrf=csrf-1 ; Expires=Wed, 21 Oct 2026 07:28:00 GMT;; os=pc",
        )

        assertEquals("token-1", client.cookie("MUSIC_U"))
        assertEquals("csrf-1", client.cookie("__csrf"))
        assertEquals("pc", client.cookie("os"))
        assertTrue(client.isLoggedIn)
        // Cookie attributes never leak into the jar.
        assertNull(client.cookie("Path"))
        assertNull(client.cookie("Expires"))
    }

    @Test
    fun `ingest cookie string skips malformed segments`() {
        client.ingestCookieString("novalue;; =emptyname;; key=;; another=value")

        val snapshot = cookieStore.snapshot()
        assertEquals(mapOf("another" to "value"), snapshot)
    }

    @Test
    fun `ingest cookie string keeps value equals signs`() {
        client.ingestCookieString("token=a=b=c")
        assertEquals("a=b=c", client.cookie("token"))
    }

    // MARK: - merge / clear

    @Test
    fun `update merges and later values win`() {
        cookieStore.update(mapOf("a" to "1", "b" to "2"))
        cookieStore.update(mapOf("b" to "3", "c" to "4"))

        assertEquals(mapOf("a" to "1", "b" to "3", "c" to "4"), cookieStore.snapshot())
    }

    @Test
    fun `clear auth cookies removes only credentials`() {
        cookieStore.update(mapOf("MUSIC_U" to "t", "__csrf" to "c", "os" to "pc"))

        client.clearAuthCookies()

        assertEquals(mapOf("os" to "pc"), cookieStore.snapshot())
        assertFalse(client.isLoggedIn)
        assertNull(client.cookie("MUSIC_U"))
        assertNull(client.cookie("__csrf"))
    }

    @Test
    fun `authentication cookies filter is credentials only`() {
        cookieStore.update(
            mapOf("MUSIC_U" to "t", "__csrf" to "c", "os" to "pc", "appver" to "3.1.17"),
        )

        assertEquals(mapOf("MUSIC_U" to "t", "__csrf" to "c"), client.authenticationCookies())
    }

    @Test
    fun `is logged in tracks the music_u cookie`() {
        assertFalse(client.isLoggedIn)
        client.ingestCookieString("MUSIC_U=abc")
        assertTrue(client.isLoggedIn)
        client.clearAuthCookies()
        assertFalse(client.isLoggedIn)
    }

    // MARK: - epoch rollback

    @Test
    fun `stale set-cookie does not roll back renewed credentials`() = runBlocking {
        val gate = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                // The csrf cookie appends "?csrf_token=…" to the path.
                if (request.path?.startsWith("/weapi/slow") == true) {
                    gate.await(10, TimeUnit.SECONDS)
                    return MockResponse()
                        .setResponseCode(200)
                        .setBody("{}")
                        .addHeader("Set-Cookie", "MUSIC_U=stale-old; Path=/")
                }
                return MockResponse().setResponseCode(200).setBody("{}")
            }
        }

        // A login is already in place; the in-flight request carries its epoch.
        client.ingestCookieString("MUSIC_U=first;; __csrf=csrf-first")

        val slow = async(Dispatchers.IO) {
            runCatching { client.weapi("/slow") }
        }
        val deadline = System.currentTimeMillis() + 5_000
        while (server.requestCount < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals(1, server.requestCount)

        // While the request is in flight, the token is renewed.
        client.ingestCookieString("MUSIC_U=renewed")
        assertEquals("renewed", client.cookie("MUSIC_U"))

        // The stale reply must neither apply its old cookie nor succeed.
        gate.countDown()
        val result = slow.await()
        assertTrue(
            "expected CancellationException but got ${result.exceptionOrNull()}",
            result.exceptionOrNull() is CancellationException,
        )
        assertEquals("renewed", client.cookie("MUSIC_U"))
    }
}
