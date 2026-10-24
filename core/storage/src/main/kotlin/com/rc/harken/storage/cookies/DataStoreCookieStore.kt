// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken.storage.cookies

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.rc.harken.net.client.CookieStore
import com.rc.harken.net.client.NeteaseClient
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
     * 的 cache 写盘，登录 Cookie/CSRF 永久丢失。
     *
     * ⚠️ 不能把挂起的 DataStore 读盘放进临界区：`load()` 在 Main 调度器上持锁
     * 挂起等 DataStore IO 时，主线程上任何 `snapshot()`/`update()` 都会阻塞等锁，
     * load 的恢复点也排在主线程队列上 → 死锁（2026-10-24 真机踩坑：首页永久
     * 骨架屏）。改用「锁外读盘 + [overlayWrites] 写覆盖层」：读盘期间发生的
     * update/clear 记下 key，回填时跳过这些 key，内存写入不会被旧盘数据覆盖。
     */
    private val cacheLock = Any()

    /**
     * 读盘窗口内被 update()/clearAuthCookies() 触碰过的 cookie 名（cacheLock 保护）。
     * [load] 回填磁盘数据时跳过它们：内存值（或已删除状态）比旧盘数据新。
     */
    private val overlayWrites = HashSet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var saveJob: kotlinx.coroutines.Job? = null

    /** Hydrates the in-memory cache from disk; call once during bootstrap. */
    suspend fun load() {
        // 挂起的读盘必须在锁外（见 cacheLock 注释的死锁警告）。
        val stored = dataStore.data.first()[COOKIES_KEY].orEmpty()
        synchronized(cacheLock) {
            cache.clear()
            for (entry in stored) {
                val separator = entry.indexOf('=')
                if (separator <= 0) continue
                val name = entry.substring(0, separator)
                // 读盘窗口内被 update 写入 / clear 删除的 key 不回填：内存状态更新。
                if (name in overlayWrites) continue
                cache[name] = entry.substring(separator + 1)
            }
            overlayWrites.clear()
            // 回写收敛：把合并后的内存态异步落盘，洗掉历史脏盘；也保证若发生
            // 第二次 load()，读到的盘数据已包含读盘窗口内的内存写入。
            schedulePersistLocked()
        }
    }

    override fun snapshot(): Map<String, String> = synchronized(cacheLock) { HashMap(cache) }

    override fun update(newCookies: Map<String, String>) {
        synchronized(cacheLock) {
            cache.putAll(newCookies)
            overlayWrites.addAll(newCookies.keys)
            schedulePersistLocked()
        }
    }

    override fun clearAuthCookies() {
        synchronized(cacheLock) {
            cache.remove(NeteaseClient.COOKIE_MUSIC_U)
            cache.remove(NeteaseClient.COOKIE_CSRF)
            overlayWrites.add(NeteaseClient.COOKIE_MUSIC_U)
            overlayWrites.add(NeteaseClient.COOKIE_CSRF)
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
