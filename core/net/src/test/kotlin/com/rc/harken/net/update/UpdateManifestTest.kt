// Tests for the online-update manifest model (pure JVM — runs on a bare JDK).
//
// These prove the version.json decoding contract used by [UpdateChecker]:
//  - all fields parse from the canonical JSON shape,
//  - unknown keys are tolerated (the production parser uses NeteaseJson which
//    sets ignoreUnknownKeys = true), and
//  - the "newer than installed" versionCode comparison behaves as the ViewModel
//    / AppGraph rely on.

package com.rc.harken.net.update

import com.rc.harken.net.client.NeteaseJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestTest {

    private val sampleJson =
        """{"versionCode":2,"versionName":"1.0.1","apkUrl":"https://x/y.apk","updateNote":"修复若干bug"}"""

    @Test
    fun `parses canonical manifest with all fields`() {
        val m = NeteaseJson.decodeFromString<UpdateManifest>(sampleJson)
        assertEquals(2, m.versionCode)
        assertEquals("1.0.1", m.versionName)
        assertEquals("https://x/y.apk", m.apkUrl)
        assertEquals("修复若干bug", m.updateNote)
        assertFalse(m.force)
    }

    @Test
    fun `ignores unknown keys without throwing`() {
        val json = """{
            "versionCode":3,
            "versionName":"1.0.2",
            "apkUrl":"https://x/z.apk",
            "updateNote":"note",
            "force":true,
            "signature":"abc123",
            "size":1048576
        }"""
        val m = NeteaseJson.decodeFromString<UpdateManifest>(json)
        assertEquals(3, m.versionCode)
        assertEquals("1.0.2", m.versionName)
        assertEquals("https://x/z.apk", m.apkUrl)
        assertTrue(m.force)
    }

    @Test
    fun `detects newer version than installed via versionCode`() {
        val manifest = NeteaseJson.decodeFromString<UpdateManifest>(sampleJson) // versionCode = 2

        // 已装版本旧 → 应判定为待更新。
        assertTrue(manifest.versionCode > 1)

        // 已装版本相同 → 不视为待更新（避免反复提示）。
        assertFalse(manifest.versionCode > 2)

        // 已装版本更新 → 不视为待更新。
        assertFalse(manifest.versionCode > 5)
    }
}
