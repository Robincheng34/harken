// Regression tests for DataStoreCookieStore.load().
//
// 守护「扫码 803 吸收 MUSIC_U 后，bootstrap() 调用 load() 重新水合」这一链路：
// 旧实现 load() 先 cache.clear() 再只跳过磁盘回填，会把已吸入内存、仍停在
// overlayWrites 里的登录 Cookie 一并抹掉 → 登录态当场丢失（扫码成功却不进入登录态）。

package com.rc.harken.storage.cookies

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.rc.harken.net.client.NeteaseClient
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreCookieStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: DataStoreCookieStore

    @Before
    fun setup() {
        val file = tmp.newFile("cookies.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create { file }
        store = DataStoreCookieStore(dataStore)
    }

    @After
    fun tearDown() {
        // 释放持久化作用域，避免 DataStore 文件锁跨用例残留。
        store.release()
    }

    @Test
    fun `load preserves in-memory cookies absorbed before load`() = runTest {
        // 模拟扫码 803：先 update 吸收 MUSIC_U（写入内存 + overlayWrites），
        // 此时持久化尚未落盘（异步）。随后 bootstrap 调用 load() 重新水合。
        store.update(
            mapOf(
                NeteaseClient.COOKIE_MUSIC_U to "token-abc",
                NeteaseClient.COOKIE_CSRF to "csrf-xyz",
            ),
        )
        store.load()
        assertEquals("token-abc", store.snapshot()[NeteaseClient.COOKIE_MUSIC_U])
        assertEquals("csrf-xyz", store.snapshot()[NeteaseClient.COOKIE_CSRF])
    }

    @Test
    fun `load does not resurrect cookies cleared during the window`() = runTest {
        // 先放一个 MUSIC_U 并落盘，再 clear 删除，load 不应把旧盘 MUSIC_U 复活。
        store.update(mapOf(NeteaseClient.COOKIE_MUSIC_U to "old-token"))
        store.load() // 触发回写收敛，确保旧值落盘。
        store.clearAuthCookies()
        store.load()
        assertNull(store.snapshot()[NeteaseClient.COOKIE_MUSIC_U])
    }

    @Test
    fun `load hydrates from disk when cache is empty`() = runTest {
        // 冷启动无 overlay：直接往同一 DataStore 写一组已存在的 Cookie（模拟旧盘），
        // 再新建空内存 store 从盘水合，应读回 k1/k2。
        val cookiesKey = stringSetPreferencesKey("cookies")
        dataStore.edit { prefs -> prefs[cookiesKey] = setOf("k1=v1", "k2=v2") }
        val store2 = DataStoreCookieStore(dataStore)
        store2.load()
        assertEquals("v1", store2.snapshot()["k1"])
        assertEquals("v2", store2.snapshot()["k2"])
        store2.release()
    }
}
