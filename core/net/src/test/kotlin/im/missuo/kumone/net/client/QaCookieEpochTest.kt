// QA-authored supplemental tests. Independent of the engineer's suite.
// Focus: the authEpoch rollback guard and other thin spots the engineer's own
// tests do not exercise.

package im.missuo.kumone.net.client

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Cookie-epoch / rollback-guard behaviour, mirroring iOS `NeteaseClient.perform`:
 * a reply that started before a credential change must not roll the renewed
 * credentials back, while an anonymous reply must still be allowed to land.
 */
class QaCookieEpochTest {

    private lateinit var server: MockWebServer
    private lateinit var store: InMemoryCookieStore
    private lateinit var client: NeteaseClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        store = InMemoryCookieStore()
        client = NeteaseClient(cookieStore = store, baseUrl = base, eapiBaseUrl = base)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun enqueue(body: String = "{}", cookies: List<String> = emptyList()) {
        val response = MockResponse().setResponseCode(200).setBody(body)
        cookies.forEach { response.addHeader("Set-Cookie", it) }
        server.enqueue(response)
    }

    /**
     * The core anti-rollback invariant, exercised as a real race: a logged-in
     * request is held in flight by the [Dispatcher], the credential is renewed
     * while it waits, and only then does the reply arrive carrying the stale
     * token. The renewed credential must survive.
     */
    @Test
    fun `stale set-cookie cannot roll back a token renewed in flight`() = runBlocking {
        val gate = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
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

        // A login is in place, so the in-flight request carries a real epoch.
        client.ingestCookieString("MUSIC_U=first;; __csrf=csrf-first")

        val slow = async(Dispatchers.IO) {
            runCatching { client.weapi("/slow") }
        }
        // Let the request reach the dispatcher, then renew the credential.
        Thread.sleep(200)
        client.ingestCookieString("MUSIC_U=second;; __csrf=csrf-second")
        gate.countDown()
        slow.await()

        assertEquals(
            "the renewed credential must survive a stale Set-Cookie",
            "second",
            client.cookie(NeteaseClient.COOKIE_MUSIC_U),
        )
    }

    /** The mirror case: a response carrying the *new* token is adopted. */
    @Test
    fun `a set-cookie matching the current token does not disturb it`() = runBlocking {
        val gate = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path?.startsWith("/weapi/slow") == true) {
                    gate.await(10, TimeUnit.SECONDS)
                    return MockResponse()
                        .setResponseCode(200)
                        .setBody("{}")
                        .addHeader("Set-Cookie", "MUSIC_U=first; Path=/")
                }
                return MockResponse().setResponseCode(200).setBody("{}")
            }
        }

        client.ingestCookieString("MUSIC_U=first;; __csrf=csrf-first")
        val slow = async(Dispatchers.IO) { runCatching { client.weapi("/slow") } }
        Thread.sleep(200)
        gate.countDown()
        slow.await()

        assertEquals("first", client.cookie(NeteaseClient.COOKIE_MUSIC_U))
    }

    /** A fresh token arriving normally must be adopted. */
    @Test
    fun `a newer set-cookie is adopted`() = runBlocking {
        store.update(mapOf(NeteaseClient.COOKIE_MUSIC_U to "TOKEN_A"))
        client.ingestCookieString("MUSIC_U=TOKEN_A;__csrf=c1")
        enqueue(
            body = "{}",
            cookies = listOf("MUSIC_U=TOKEN_B; Path=/", "__csrf=c2; Path=/"),
        )
        client.weapi("/song/detail")
        assertEquals("TOKEN_B", client.cookie(NeteaseClient.COOKIE_MUSIC_U))
        assertEquals("c2", client.cookie(NeteaseClient.COOKIE_CSRF))
    }

    /**
     * An anonymous request (no login) in flight while a login completes must
     * still succeed — the response carries no account data, so it is not stale.
     */
    @Test
    fun `anonymous request survives a concurrent login`() = runBlocking {
        enqueue(body = """{"code":200}""")
        // The client is anonymous here (no MUSIC_U yet).
        val body = client.weapi("/song/detail")
        assertTrue("anonymous request must not be cancelled", body.contains("200"))
    }

    /** Logging out bumps the epoch, so any in-flight logged-in reply is stale. */
    @Test
    fun `clearAuthCookies drops credentials and isLoggedIn`() {
        store.update(mapOf(NeteaseClient.COOKIE_MUSIC_U to "T", NeteaseClient.COOKIE_CSRF to "C"))
        assertTrue(client.isLoggedIn)
        client.clearAuthCookies()
        assertNull(client.cookie(NeteaseClient.COOKIE_MUSIC_U))
        assertNull(client.cookie(NeteaseClient.COOKIE_CSRF))
    }

    /** Non-credential cookies survive a logout (os/appver are decoration). */
    @Test
    fun `clearAuthCookies keeps transport cookies`() {
        store.update(
            mapOf(
                NeteaseClient.COOKIE_MUSIC_U to "T",
                "os" to "pc",
                "appver" to "3.1.17",
                "NMTID" to "abc",
            ),
        )
        client.clearAuthCookies()
        assertEquals("pc", client.cookie("os"))
        assertEquals("3.1.17", client.cookie("appver"))
        assertEquals("abc", client.cookie("NMTID"))
    }

    /** Two distinct tokens must produce distinct fingerprints (epoch identity). */
    @Test
    fun `distinct tokens yield distinct sessions`() {
        client.ingestCookieString("MUSIC_U=TOKEN_X;__csrf=c")
        val first = client.authenticationCookies()[NeteaseClient.COOKIE_MUSIC_U]
        client.ingestCookieString("MUSIC_U=TOKEN_Y;__csrf=c")
        val second = client.authenticationCookies()[NeteaseClient.COOKIE_MUSIC_U]
        assertNotEquals(first, second)
        assertEquals("TOKEN_Y", second)
    }

    /** A Set-Cookie that expires a cookie to an empty value must not blank it. */
    @Test
    fun `empty set-cookie value does not erase a credential`() = runBlocking {
        client.ingestCookieString("MUSIC_U=KEEPME;__csrf=c")
        enqueue(body = "{}", cookies = listOf("MUSIC_U=; Path=/", "NMTID=; Path=/"))
        client.weapi("/song/detail")
        assertEquals("KEEPME", client.cookie(NeteaseClient.COOKIE_MUSIC_U))
    }

    /** Malformed Set-Cookie segments are skipped, not fatal. */
    @Test
    fun `malformed set-cookie headers are skipped`() = runBlocking {
        client.ingestCookieString("MUSIC_U=KEEPME;__csrf=c")
        enqueue(
            body = "{}",
            cookies = listOf(
                "novalue; Path=/",
                "=noname; Path=/",
                "MUSIC_U=KEEPME; Path=/",
            ),
        )
        val body = client.weapi("/song/detail")
        assertEquals("{}", body)
        assertEquals("KEEPME", client.cookie(NeteaseClient.COOKIE_MUSIC_U))
    }

    /** The raw QR cookie string uses `;;` separators and mixed whitespace. */
    @Test
    fun `raw cookie string tolerates whitespace and separators`() {
        client.ingestCookieString("MUSIC_U = TOK ;; __csrf = CS ;; bad ;; = x")
        assertEquals("TOK", client.cookie(NeteaseClient.COOKIE_MUSIC_U))
        assertEquals("CS", client.cookie(NeteaseClient.COOKIE_CSRF))
    }

    /** An HTTP error status still surfaces as a typed error, not a silent pass. */
    @Test
    fun `http 500 surfaces as Http error`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
        val thrown = runCatching { runBlocking { client.weapi("/song/detail") } }.exceptionOrNull()
        assertTrue(
            "expected an error for HTTP 500, got $thrown",
            thrown is im.missuo.kumone.net.api.NeteaseApiError.Http,
        )
    }

    /** The csrf token rides in both the body and the query string. */
    @Test
    fun `csrf token appears in body and query`() = runBlocking {
        client.ingestCookieString("MUSIC_U=T;; __csrf=CSRF123")
        enqueue(body = "{}")
        runCatching { client.weapi("/user/account") }
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertTrue(
            "csrf must be in the query: ${recorded.path}",
            recorded.path!!.contains("csrf_token=CSRF123"),
        )
        assertEquals(
            "application/x-www-form-urlencoded",
            recorded.getHeader("Content-Type"),
        )
    }
}