// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.net.client

import im.missuo.kumone.net.api.NeteaseApiError
import im.missuo.kumone.net.crypto.NeteaseCrypto
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The single JSON codec of the protocol layer: unknown keys are ignored and
 * nulls coerce to property defaults, mirroring the tolerant iOS Codables.
 */
internal val NeteaseJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/**
 * Transport layer for NetEase Cloud Music. Owns the cookie jar and performs
 * weapi / eapi encrypted requests.
 *
 * Ported 1:1 from `NeteaseClient.swift`: cookie management, the login
 * session-binding fingerprint and the `authEpoch` rollback guard all behave
 * like the iOS original. Requests run on [Dispatchers.IO]; callers do not need
 * to care about threading.
 */
class NeteaseClient(
    private val cookieStore: CookieStore = InMemoryCookieStore(),
    private val baseUrl: String = "https://music.163.com",
    private val eapiBaseUrl: String = "https://interface.music.163.com",
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {

    private val lock = Any()

    /** Bumped on every credential change; requests carry the epoch they started with. */
    private var authEpoch: Long = 0L

    /** Fingerprint of the current login; survives token renewals of the same login. */
    private var sessionBinding: String? = null

    /** True when a `MUSIC_U` cookie is present. */
    val isLoggedIn: Boolean get() = cookie(COOKIE_MUSIC_U) != null

    private class AuthenticationState(val epoch: Long, val binding: String?)

    private val authenticationState: AuthenticationState
        get() = synchronized(lock) {
            ensureSessionBinding()
            AuthenticationState(authEpoch, sessionBinding)
        }

    private fun isCurrent(authentication: AuthenticationState): Boolean = synchronized(lock) {
        ensureSessionBinding()
        authentication.epoch == authEpoch ||
            (authentication.binding != null && authentication.binding == sessionBinding)
    }

    /** Lazily derives the binding for jars hydrated before the first request (cold start). */
    private fun ensureSessionBinding() {
        if (sessionBinding == null) {
            sessionBinding = cookieStore.snapshot()[COOKIE_MUSIC_U]?.let(::fingerprint)
        }
    }

    private fun fingerprint(token: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .toHexLower()

    /** The login credentials only (`MUSIC_U` / `__csrf`). */
    fun authenticationCookies(): Map<String, String> =
        cookieStore.snapshot().filter { it.key == COOKIE_MUSIC_U || it.key == COOKIE_CSRF }

    fun cookie(name: String): String? = cookieStore.snapshot()[name]

    /**
     * Merges new cookies. Returns false when [expectedEpoch] no longer matches,
     * i.e. the credentials were replaced while the response was in flight.
     * [deletions] names server-driven removals (expired / emptied Set-Cookie).
     */
    private fun setCookies(
        new: Map<String, String>,
        expectedEpoch: Long? = null,
        preservingSession: Boolean = false,
        deletions: Set<String> = emptySet(),
        source: String = "",
    ): Boolean {
        synchronized(lock) {
            if (expectedEpoch != null && expectedEpoch != authEpoch) return false
            val token = new[COOKIE_MUSIC_U]
            if (token != null && token != cookieStore.snapshot()[COOKIE_MUSIC_U]) {
                authEpoch += 1
                if (!preservingSession || sessionBinding == null) sessionBinding = fingerprint(token)
            }
            if (COOKIE_MUSIC_U in deletions && COOKIE_MUSIC_U !in new) {
                // 服务端撤销了登录凭证：等同登出，递增 epoch 使在途鉴权请求失效。
                // 诊断日志：core/net 是纯 JVM 模块（无 android.util.Log），走 stderr
                // 输出（logcat 显示为 System.err 流），定位 MUSIC_U 删除来源——
                // 是服务端显式撤销（Max-Age=0/过期）还是本地误判。
                System.err.println(
                    "[NeteaseClient] MUSIC_U deletion directive received; " +
                        "source=$source deletions=$deletions — logging out",
                )
                authEpoch += 1
                sessionBinding = null
            }
            val merged = LinkedHashMap(cookieStore.snapshot())
            merged.putAll(new)
            for (name in deletions) merged.remove(name)
            merged.remove(BINDING_KEY)
            cookieStore.update(merged)
            return true
        }
    }

    /** Ingests a `;;`-joined raw cookie string as returned by the QR login check. */
    fun ingestCookieString(raw: String) {
        val parsed = LinkedHashMap<String, String>()
        for (cookie in raw.split(COOKIE_STRING_SEPARATOR)) {
            val pair = cookie.substringBefore(';')
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val name = pair.substring(0, eq).trim()
            val value = pair.substring(eq + 1).trim()
            if (name.isEmpty() || value.isEmpty()) continue
            parsed[name] = value
        }
        setCookies(parsed)
    }

    /** Drops the login credentials and invalidates every in-flight authentication. */
    fun clearAuthCookies() {
        synchronized(lock) {
            authEpoch += 1
            sessionBinding = null
        }
        cookieStore.clearAuthCookies()
    }

    private fun cookieHeader(extra: Map<String, String>, overrides: Map<String, String>): String {
        val all = LinkedHashMap(cookieStore.snapshot())
        for ((key, value) in extra) {
            if (!all.containsKey(key)) all[key] = value
        }
        all.putAll(overrides)
        return all.entries.joinToString(separator = "; ") { "${it.key}=${it.value}" }
    }

    private fun absorbSetCookies(
        response: Response,
        epoch: Long,
        preservingSession: Boolean,
        source: String = "",
    ): Boolean {
        val parsed = parseSetCookieHeaders(response.headers(HEADER_SET_COOKIE))
        if (parsed.upserts.isEmpty() && parsed.deletions.isEmpty()) return true
        return setCookies(
            parsed.upserts,
            expectedEpoch = epoch,
            preservingSession = preservingSession,
            deletions = parsed.deletions,
            source = source,
        )
    }

    /** 一次 Set-Cookie 解析结果：[upserts] 为要写入的键值，[deletions] 为要移除的键。 */
    private class SetCookieBatch(
        val upserts: LinkedHashMap<String, String>,
        val deletions: LinkedHashSet<String>,
    )

    /**
     * 解析响应的 Set-Cookie 头。
     *
     * 原 bug：空值被直接跳过、Max-Age/Expires 完全被忽略，服务端无法用
     * `MUSIC_U=; Max-Age=0` 撤销凭证。现按 RFC 6265 语义归一：`Max-Age<=0`
     * （或非法值）与已过期的 `Expires` 视为删除指令；空值本身不是删除指令
     * （无过期属性的空值是合法的空会话 cookie），为避免误伤既有凭证，
     * 此类条目直接忽略（既不写入也不删除）。
     */
    private fun parseSetCookieHeaders(headers: List<String>): SetCookieBatch {
        val upserts = LinkedHashMap<String, String>()
        val deletions = LinkedHashSet<String>()
        for (header in headers) {
            val segments = header.split(';')
            val pair = segments.first().trim()
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val name = pair.substring(0, eq).trim()
            val value = pair.substring(eq + 1).trim()
            val emptyValue = value.isEmpty() || value == "\"\""
            var expiredByAttributes = false
            for (attribute in segments.drop(1)) {
                val trimmed = attribute.trim()
                val attrEq = trimmed.indexOf('=')
                val attrName =
                    (if (attrEq <= 0) trimmed else trimmed.substring(0, attrEq))
                        .lowercase(Locale.ROOT)
                val attrValue = if (attrEq <= 0) "" else trimmed.substring(attrEq + 1).trim()
                when (attrName) {
                    // Max-Age 非数字/<=0 均按过期（删除指令）处理。
                    "max-age" -> if (attrValue.toLongOrNull()?.let { it > 0 } != true) expiredByAttributes = true
                    "expires" -> {
                        val expires = parseHttpDate(attrValue)
                        if (expires != null && expires.isBefore(Instant.now())) expiredByAttributes = true
                    }
                }
            }
            when {
                expiredByAttributes -> {
                    deletions.add(name)
                    upserts.remove(name)
                }
                // 无过期属性的空值：忽略，不覆盖既有凭证（QA 契约：空值不抹凭证）。
                emptyValue -> Unit
                else -> upserts[name] = value
            }
        }
        return SetCookieBatch(upserts, deletions)
    }

    /** RFC 1123 日期（如 `Tue, 03 Jun 2008 11:05:30 GMT`）；不可解析时返回 null（保守视为未过期）。 */
    private fun parseHttpDate(value: String): Instant? = try {
        OffsetDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
    } catch (e: DateTimeParseException) {
        null
    } catch (e: DateTimeException) {
        null
    }

    // MARK: - Requests

    /** POST to `{base}/weapi{path}` with weapi encryption. Returns the raw JSON body. */
    suspend fun weapi(
        path: String,
        payload: Map<String, Any?> = emptyMap(),
        cookieOverrides: Map<String, String> = emptyMap(),
        absorbResponseCookies: Boolean = true,
    ): String {
        val authentication = authenticationState
        val csrf = cookieOverrides[COOKIE_CSRF] ?: cookie(COOKIE_CSRF)
        val body = LinkedHashMap(payload)
        body["csrf_token"] = csrf ?: ""
        val form = NeteaseCrypto.weapi(encodePayload(body))

        var fullPath = path
        if (!csrf.isNullOrEmpty()) {
            fullPath += (if (fullPath.contains("?")) "&" else "?") + "csrf_token=$csrf"
        }
        val request = Request.Builder()
            .url("$baseUrl/weapi$fullPath")
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .header("Content-Type", FORM_CONTENT_TYPE)
            .header(
                "Cookie",
                cookieHeader(
                    extra = mapOf("os" to "pc", "appver" to APP_VERSION),
                    overrides = cookieOverrides,
                ),
            )
            .post(encodeForm(form))
            .build()
        return perform(request, authentication, absorbResponseCookies)
    }

    /**
     * POST to `{eapiBase}/eapi{path}` with eapi encryption; the digest is
     * computed over the corresponding `/api{path}` path. Returns the raw JSON body.
     */
    suspend fun eapi(
        path: String,
        payload: Map<String, Any?> = emptyMap(),
        cookieOverrides: Map<String, String> = emptyMap(),
    ): String {
        val authentication = authenticationState
        val apiPath = "/api$path"
        val header = LinkedHashMap<String, Any?>(
            linkedMapOf(
                "os" to "pc",
                "appver" to APP_VERSION,
                "osver" to "Version 14.0 (Build 23A344)",
                "deviceId" to "kumone",
                "requestId" to (20_000_000..30_000_000).random().toString(),
                "clientSign" to "",
                "versioncode" to "140",
                "buildver" to System.currentTimeMillis() / 1000,
                "resolution" to "1920x1080",
                "channel" to "",
            ),
        )
        cookie(COOKIE_MUSIC_U)?.let { header[COOKIE_MUSIC_U] = it }
        cookie(COOKIE_CSRF)?.let { header[COOKIE_CSRF] = it }
        val body = LinkedHashMap(payload)
        body["header"] = header
        val form = NeteaseCrypto.eapi(apiPath, encodePayload(body))

        val request = Request.Builder()
            .url("$eapiBaseUrl/eapi$path")
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .header("Content-Type", FORM_CONTENT_TYPE)
            .header(
                "Cookie",
                cookieHeader(
                    extra = mapOf("os" to "pc", "appver" to APP_VERSION),
                    overrides = cookieOverrides,
                ),
            )
            .post(encodeForm(form))
            .build()
        return perform(request, authentication, absorbResponseCookies = true)
    }

    private suspend fun perform(
        request: Request,
        authentication: AuthenticationState,
        absorbResponseCookies: Boolean = true,
    ): String = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        // A request sent without a login carries no account's data, so a login
        // finishing while it is in flight does not make its answer stale.
        val anonymous = authentication.binding == null
        if (!anonymous && !isCurrent(authentication)) {
            throw CancellationException("authentication superseded")
        }
        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: IOException) {
            throw NeteaseApiError.Network(e)
        }
        // 原 bug：包装只覆盖 execute()，读体阶段（body?.string()）弱网断连会抛裸
        // IOException，调用方的 NeteaseApiError.Network 归一化失效。现扩大到整个
        // use 块（CancellationException 除外，原样重抛）。
        try {
            response.use { active ->
                if (!anonymous && !isCurrent(authentication)) {
                    throw CancellationException("authentication superseded")
                }
                val body = active.body?.string().orEmpty()
                val refreshSucceeded =
                    active.request.url.encodedPath == "/weapi/login/token/refresh" &&
                        active.code in HTTP_SUCCESS && businessCode(body) == BUSINESS_CODE_OK
                if (absorbResponseCookies &&
                    !absorbSetCookies(
                        active,
                        authentication.epoch,
                        preservingSession = refreshSucceeded,
                        source = active.request.url.encodedPath,
                    )
                ) {
                    // A reply from before a token renewal still belongs to this
                    // login, but its older Set-Cookie must not roll back the
                    // renewed credentials.
                    if (!isCurrent(authentication)) {
                        throw CancellationException("authentication superseded")
                    }
                }
                if (active.code !in HTTP_SUCCESS) {
                    throw NeteaseApiError.Http(active.code)
                }
                body
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw NeteaseApiError.Network(e)
        }
    }

    // MARK: - Payload / form encoding

    /** Serializes a payload map to JSON bytes; insertion order is preserved. */
    private fun encodePayload(payload: Map<String, Any?>): ByteArray {
        val jsonObject = buildJsonObject {
            for ((key, value) in payload) {
                put(key, value.toJsonElement())
            }
        }
        return NeteaseJson.encodeToString(JsonObject.serializer(), jsonObject)
            .toByteArray(Charsets.UTF_8)
    }

    /**
     * Form-encodes the encrypted fields. Only unreserved characters
     * (`A-Za-z0-9-._~`) pass through; base64's `+/=` are percent-encoded, as
     * on iOS (`CharacterSet.alphanumerics + "-._~"`).
     */
    private fun encodeForm(fields: Map<String, String>): RequestBody {
        val body = fields.entries.joinToString(separator = "&") { (key, value) ->
            "$key=${percentEncode(value)}"
        }
        // No media type on the body: OkHttp would otherwise override the
        // explicit Content-Type header with "...; charset=utf-8", while iOS
        // sends the bare "application/x-www-form-urlencoded".
        return body.toRequestBody(null)
    }

    private fun percentEncode(value: String): String = buildString(value.length) {
        for (character in value) {
            if (character in UNRESERVED_CHARACTERS) {
                append(character)
            } else {
                for (byte in character.toString().toByteArray(Charsets.UTF_8)) {
                    val intValue = byte.toInt() and 0xFF
                    append('%')
                    append(PERCENT_ENCODE_HEX[intValue ushr 4])
                    append(PERCENT_ENCODE_HEX[intValue and 0x0F])
                }
            }
        }
    }

    /** The business `code` field of a response body, or null when absent/unparsable. */
    internal fun businessCode(body: String): Int? {
        val element = try {
            NeteaseJson.parseToJsonElement(body)
        } catch (e: SerializationException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        return (element as? JsonObject)?.get("code")?.asIntOrNullCompat()
    }

    private fun JsonElement.asIntOrNullCompat(): Int? = when (this) {
        is JsonPrimitive -> content.trim().toIntOrNull()
        else -> null
    }

    companion object {
        const val USER_AGENT: String =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
        const val REFERER: String = "https://music.163.com"
        const val APP_VERSION: String = "3.1.17"

        const val BUSINESS_CODE_OK: Int = 200
        const val BUSINESS_CODE_NEED_LOGIN: Int = 301

        /**
         * The login-credential cookie names. Public because they are part of
         * the [CookieStore] contract vocabulary: every implementation (the
         * DataStore one in `:core:storage` included) must know them to honour
         * `clearAuthCookies()`.
         */
        /** 登录态主 Cookie（网易云标准名）。跨模块契约常量，公开可见。 */
        const val COOKIE_MUSIC_U = "MUSIC_U"
        /** CSRF 校验 Cookie（网易云标准名）。 */
        const val COOKIE_CSRF = "__csrf"

        internal const val BINDING_KEY = "__kumone_session_binding"
        internal const val COOKIE_STRING_SEPARATOR = ";;"
        internal const val HEADER_SET_COOKIE = "Set-Cookie"
        internal const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded"
        internal val HTTP_SUCCESS = 200 until 300
        internal val UNRESERVED_CHARACTERS: Set<Char> =
            (('A'..'Z') + ('a'..'z') + ('0'..'9') + "-._~".toSet()).toSet()
        internal val PERCENT_ENCODE_HEX = "0123456789ABCDEF".toCharArray()

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            // Cookies are managed by this class itself (iOS parity).
            .cookieJar(CookieJar.NO_COOKIES)
            .build()
    }
}

internal fun ByteArray.toHexLower(): String = buildString(size * 2) {
    val hex = "0123456789abcdef".toCharArray()
    for (byte in this@toHexLower) {
        val value = byte.toInt() and 0xFF
        append(hex[value ushr 4])
        append(hex[value and 0x0F])
    }
}

/**
 * Converts a payload value (String / Number / Boolean / null / List / Map /
 * JsonElement) into a [JsonElement]; shared with the API layer's weblog bodies.
 */
internal fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Int -> JsonPrimitive(this)
    is Long -> JsonPrimitive(this)
    is Double -> JsonPrimitive(this)
    is Float -> JsonPrimitive(this)
    is List<*> -> JsonArray(map { it.toJsonElement() })
    is Map<*, *> -> buildJsonObject {
        for ((key, value) in this@toJsonElement) {
            put(key.toString(), value.toJsonElement())
        }
    }
    else -> JsonPrimitive(toString())
}
