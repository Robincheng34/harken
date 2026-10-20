// Ported from kumone by missuo (LGPL-3.0)
//
// 在线检测更新：自托管 version.json 的清单模型与拉取器。
// 复用 core:net 的 [NeteaseJson]（ignoreUnknownKeys=true）做容错解析。

package im.missuo.kumone.net.update

import im.missuo.kumone.net.client.NeteaseJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class UpdateManifest(
    val versionCode: Int = 0,
    val versionName: String = "",
    val apkUrl: String = "",
    val updateNote: String = "",
    val force: Boolean = false,
)

sealed class UpdateCheckException : Exception() {
    data class Http(val code: Int) : UpdateCheckException()
    data class Network(override val cause: Throwable) : UpdateCheckException()
    data class Decode(override val cause: Throwable) : UpdateCheckException()
    object Empty : UpdateCheckException()
}

class UpdateChecker(
    private val httpClient: OkHttpClient = defaultClient(),
    val manifestUrl: String = UPDATE_MANIFEST_URL,
) {
    suspend fun fetchManifest(url: String = manifestUrl): UpdateManifest = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .header("User-Agent", "Kumone-UpdateChecker").build()
        try {
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw UpdateCheckException.Http(resp.code)
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) throw UpdateCheckException.Empty
                NeteaseJson.decodeFromString<UpdateManifest>(body)
            }
        } catch (e: UpdateCheckException) {
            throw e
        } catch (e: IOException) {
            throw UpdateCheckException.Network(e)
        } catch (e: Exception) {
            throw UpdateCheckException.Decode(e)
        }
    }

    companion object {
        // 默认 OkHttpClient（构造参数默认值在静态上下文求值，故置于 companion）。
        internal fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        // 用户自托管地址：发布前改成你自己的 version.json（建议放在境内可达的 OSS/COS + CDN，确保国内可访问）。
        const val UPDATE_MANIFEST_URL = "https://example.com/kumone/version.json"
    }
}
