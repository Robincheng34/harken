// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken.net.client

/**
 * Cookie jar contract for the NetEase transport layer.
 *
 * Only `MUSIC_U` / `__csrf` are login credentials; the rest (os/appver hints)
 * is transport decoration. Implementations must be thread-safe: the pure-JVM
 * `InMemoryCookieStore` serves tests, the DataStore-backed implementation
 * (`:core:storage`) serves production.
 */
interface CookieStore {
    /** A consistent copy of all current cookies. */
    fun snapshot(): Map<String, String>

    /** Merges [newCookies] into the jar (values win; the binding key is ignored). */
    fun update(newCookies: Map<String, String>)

    /** Removes only the login credentials (`MUSIC_U` / `__csrf`). */
    fun clearAuthCookies()
}

/** In-memory [CookieStore] for tests and previews. */
class InMemoryCookieStore(initial: Map<String, String> = emptyMap()) : CookieStore {

    private val cookies: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()

    init {
        cookies.putAll(initial)
    }

    override fun snapshot(): Map<String, String> = HashMap(cookies)

    override fun update(newCookies: Map<String, String>) {
        cookies.putAll(newCookies)
    }

    override fun clearAuthCookies() {
        cookies.remove(NeteaseClient.COOKIE_MUSIC_U)
        cookies.remove(NeteaseClient.COOKIE_CSRF)
    }
}
