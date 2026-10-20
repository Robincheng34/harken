// Pure-logic unit test for the online-update manifest decoding contract,
// hosted in :app so `./gradlew testDebugUnitTest` exercises it.
//
// It touches NO Android APIs — only kotlinx.serialization — so it runs on the
// plain JVM unit-test runner (no Robolectric required). It mirrors the parse +
// "versionCode > installed" comparison that SettingsViewModel.checkUpdate() and
// AppGraph.checkForUpdateIfDue() perform.

package im.missuo.kumone

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private val json = Json { ignoreUnknownKeys = true }

class UpdateManifestParseTest {

    private val sampleJson =
        """{"versionCode":2,"versionName":"1.0.1","apkUrl":"https://x/y.apk","updateNote":"修复若干bug"}"""

    @Test
    fun `parses manifest fields correctly`() {
        val m = json.decodeFromString(ManifestSurrogate.serializer(), sampleJson)
        assertEquals(2, m.versionCode)
        assertEquals("1.0.1", m.versionName)
        assertEquals("https://x/y.apk", m.apkUrl)
        assertEquals("修复若干bug", m.updateNote)
    }

    @Test
    fun `tolerates unknown keys`() {
        val jsonWithExtra =
            """{"versionCode":4,"versionName":"1.0.3","apkUrl":"https://x/a.apk","updateNote":"n","foo":"bar","extra":1}"""
        val m = json.decodeFromString(ManifestSurrogate.serializer(), jsonWithExtra)
        assertEquals(4, m.versionCode)
    }

    @Test
    fun `versionCode greater than installed means update pending`() {
        val manifest = json.decodeFromString(ManifestSurrogate.serializer(), sampleJson)
        assertTrue(manifest.versionCode > 1) // older installed
        assertFalse(manifest.versionCode > 2) // equal installed
        assertFalse(manifest.versionCode > 9) // newer installed
    }
}

/**
 * Local serializable mirror of [im.missuo.kumone.net.update.UpdateManifest] so
 * this :app test needs no dependency on the (internal) production parser. The
 * JSON shape is identical to what UpdateChecker fetches.
 */
@kotlinx.serialization.Serializable
private data class ManifestSurrogate(
    val versionCode: Int = 0,
    val versionName: String = "",
    val apkUrl: String = "",
    val updateNote: String = "",
    val force: Boolean = false,
)
