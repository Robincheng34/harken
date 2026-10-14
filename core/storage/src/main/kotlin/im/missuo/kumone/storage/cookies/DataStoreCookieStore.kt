// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.storage.cookies

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import im.missuo.kumone.net.client.CookieStore
import im.missuo.kumone.net.client.NeteaseClient
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * DataStore-backed [CookieStore] (iOS `CookieStore` + persistence).
 *
 * Reads ([snapshot]) are synchronous against an in-memory cache so the
 * transport layer never blocks on disk; writes update the cache first and
 * debounce-persist the whole jar (a handful of cookies) as a single
 * `name=value` string set. Call [load] once at cold start.
 */
class DataStoreCookieStore(private val dataStore: DataStore<Preferences>) : CookieStore {

    private val cache = ConcurrentHashMap<String, String>()

    /**
     * 保护 cache 复合操作的互斥锁。
     *
     * 原 bug：`load()` 的 clear+回填段与并发的 `update()`（HTTP 响应 absorb 写入）
     * 竞态，期间写入的 Cookie 会被 clear() 抹掉，且随后调度的持久化 job 把清空后
     * 的 cache 写盘，登录 Cookie/CSRF 永久丢失。现让所有 cache 段互斥（挂起的
     * DataStore 读取放在锁外，`update()` 是同步接口方法故用 `synchronized`）。
     */
    private val cacheLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var saveJob: kotlinx.coroutines.Job? = null

    /** Hydrates the in-memory cache from disk; call once during bootstrap. */
    suspend fun load() {
        // 挂起的 DataStore 读取放在锁外，避免长时间持锁。
        val stored = dataStore.data.first()[COOKIES_KEY].orEmpty()
        synchronized(cacheLock) {
            cache.clear()
            for (entry in stored) {
                val separator = entry.indexOf('=')
                if (separator <= 0) continue
                cache[entry.substring(0, separator)] = entry.substring(separator + 1)
            }
        }
    }

    override fun snapshot(): Map<String, String> = synchronized(cacheLock) { HashMap(cache) }

    override fun update(newCookies: Map<String, String>) {
        synchronized(cacheLock) {
            cache.putAll(newCookies)
            schedulePersistLocked()
        }
    }

    override fun clearAuthCookies() {
        synchronized(cacheLock) {
            cache.remove(NeteaseClient.COOKIE_MUSIC_U)
            cache.remove(NeteaseClient.COOKIE_CSRF)
            schedulePersistLocked()
        }
    }

    /** Releases the persistence scope; the cache stays usable. */
    fun release() {
        scope.cancel()
    }

    /** 须在持有 [cacheLock] 时调用：持锁取一致快照，持久化数据不会被并发的清空抹掉。 */
    private fun schedulePersistLocked() {
        saveJob?.cancel()
        val snapshot = HashMap(cache)
        saveJob = scope.launch {
            dataStore.edit { preferences ->
                preferences[COOKIES_KEY] = snapshot.entries
                    .map { "${it.key}=${it.value}" }
                    .toSet()
            }
        }
    }

    private companion object {
        val COOKIES_KEY = stringSetPreferencesKey("cookies")
    }
}
