// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.net.crypto

import java.io.FileNotFoundException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@Serializable
internal data class WeapiGoldenCase(
    val name: String,
    val payload: String,
    val params: String,
    val encSecKey: String,
)

@Serializable
internal data class WeapiGoldenFile(val cases: List<WeapiGoldenCase>)

@Serializable
internal data class EapiGoldenCase(
    val name: String,
    val apiPath: String,
    val payload: String,
    val params: String,
)

@Serializable
internal data class EapiGoldenFile(val cases: List<EapiGoldenCase>)

/**
 * Golden-vector tests for the weapi / eapi encryption.
 *
 * The fixtures under `src/test/resources/fixtures/` were recomputed from the
 * iOS implementation (`NeteaseCrypto.swift`) with node:crypto; the Kotlin
 * output must match them byte-for-byte. The weapi preset key/IV and the
 * precomputed encSecKey were additionally cross-checked against the public
 * NetEase weapi algorithm (`encSecKey = RSA(reverse(secretKey))`, exponent
 * 010001, the well-known 1024-bit modulus).
 */
class NeteaseCryptoTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadResource(path: String): String {
        val stream = javaClass.getResourceAsStream(path)
            ?: throw FileNotFoundException("missing test resource: $path")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun weapiGolden(): WeapiGoldenFile =
        json.decodeFromString(loadResource("/fixtures/weapi-golden.json"))

    private fun eapiGolden(): EapiGoldenFile =
        json.decodeFromString(loadResource("/fixtures/eapi-golden.json"))

    @Test
    fun `weapi matches golden vectors byte for byte`() {
        for (case in weapiGolden().cases) {
            val form = NeteaseCrypto.weapi(case.payload.toByteArray(Charsets.UTF_8))
            assertEquals("params mismatch for ${case.name}", case.params, form["params"])
            assertEquals("encSecKey mismatch for ${case.name}", case.encSecKey, form["encSecKey"])
        }
    }

    @Test
    fun `eapi matches golden vectors byte for byte`() {
        for (case in eapiGolden().cases) {
            val form = NeteaseCrypto.eapi(
                case.apiPath,
                case.payload.toByteArray(Charsets.UTF_8),
            )
            assertEquals("params mismatch for ${case.name}", case.params, form["params"])
        }
    }

    @Test
    fun `eapi params are uppercase hex`() {
        for (case in eapiGolden().cases) {
            val params = NeteaseCrypto.eapi(
                case.apiPath,
                case.payload.toByteArray(Charsets.UTF_8),
            ).getValue("params")
            assertTrue("params must be uppercase hex for ${case.name}", params.all { it in HEX_DIGITS })
            // AES-128-ECB of a PKCS7-padded block: always a multiple of 16 bytes.
            assertEquals("hex length must be a multiple of 32", 0, params.length % 32)
        }
    }

    @Test
    fun `weapi is deterministic and payload sensitive`() {
        val a = NeteaseCrypto.weapi("""{"id":1}""".toByteArray())
        val b = NeteaseCrypto.weapi("""{"id":1}""".toByteArray())
        val c = NeteaseCrypto.weapi("""{"id":2}""".toByteArray())
        assertEquals(a, b)
        assertTrue(a.getValue("params") != c.getValue("params"))
    }

    @Test
    fun `eapi is deterministic and payload sensitive`() {
        val path = "/api/toplist"
        val a = NeteaseCrypto.eapi(path, "{}".toByteArray())
        val b = NeteaseCrypto.eapi(path, "{}".toByteArray())
        val c = NeteaseCrypto.eapi(path, """{"x":1}""".toByteArray())
        assertEquals(a, b)
        assertTrue(a.getValue("params") != c.getValue("params"))
    }

    @Test
    fun `eapi digest depends on api path`() {
        val payload = "{}".toByteArray()
        val toplist = NeteaseCrypto.eapi("/api/toplist", payload).getValue("params")
        val search = NeteaseCrypto.eapi("/api/cloudsearch/pc", payload).getValue("params")
        assertTrue(toplist != search)
    }

    private companion object {
        const val HEX_DIGITS = "0123456789ABCDEF"
    }
}
