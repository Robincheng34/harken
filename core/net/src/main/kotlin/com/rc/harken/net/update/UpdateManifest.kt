// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 在线检测更新：默认更新源为 GitHub Releases（公开仓库 Robincheng34/harken），
// 同时保留自托管 version.json 清单的兼容路径。两种来源统一归一为
// [UpdateManifest]，上层（UpdateRepository / SettingsViewModel）无需感知差异。
// 复用 core:net 的 [NeteaseJson]（ignoreUnknownKeys=true）做容错解析。

package com.rc.harken.net.update

import com.rc.harken.net.client.NeteaseJson
import kotlinx.coroutines.CancellationException
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

/** [UpdateCheckException.Http] 的用户可读文案（设置页直接展示 `e.message`）。 */
private fun updateHttpMessage(code: Int): String = when (code) {
    403, 429 -> "请求过于频繁，请稍后再试"
    404 -> "更新源不存在"
    else -> "服务器返回 $code"
}

sealed class UpdateCheckException(message: String? = null) : Exception(message) {
    data class Http(val code: Int) : UpdateCheckException(updateHttpMessage(code))
    data class Network(override val cause: Throwable) : UpdateCheckException()
    data class Decode(override val cause: Throwable) : UpdateCheckException()
    object Empty : UpdateCheckException()
}

/**
 * GitHub `releases/latest` 响应里我们用到的字段子集。
 *
 * GitHub 返回几十个字段（author / assets_url / tarball_url …），靠
 * [NeteaseJson] 的 `ignoreUnknownKeys = true` 从容忽略。
 */
@Serializable
private data class GitHubRelease(
    val tag_name: String = "",
    val name: String = "",
    val body: String = "",
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
private data class GitHubAsset(
    val name: String = "",
    val browser_download_url: String = "",
    val size: Long = 0,
)

class UpdateChecker(
    private val httpClient: OkHttpClient = defaultClient(),
    val manifestUrl: String = UPDATE_MANIFEST_URL,
) {

    /**
     * 按更新源 URL 形态分派：GitHub API → [fetchFromGitHub]；其它 → [fetchManifest]
     * （自托管 version.json）。
     *
     * 保留独立的分派入口，使 `getUpdateSourceUrl()` 的「自定义地址优先」语义不变：
     * 用户填 GitHub API 地址走 Releases 解析，填自托管清单地址走原 JSON 解析。
     */
    suspend fun fetch(url: String = manifestUrl): UpdateManifest =
        if (isGitHubReleaseUrl(url)) fetchFromGitHub(url) else fetchManifest(url)

    /** 拉取自托管 `version.json` 清单（原有契约，签名不变）。 */
    suspend fun fetchManifest(url: String = manifestUrl): UpdateManifest = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT).build()
        try {
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw UpdateCheckException.Http(resp.code)
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) throw UpdateCheckException.Empty
                NeteaseJson.decodeFromString<UpdateManifest>(body)
            }
        } catch (e: UpdateCheckException) {
            throw e
        } catch (e: CancellationException) {
            // 协程取消原样重抛，不能误报成「解析失败」。
            throw e
        } catch (e: IOException) {
            throw UpdateCheckException.Network(e)
        } catch (e: Exception) {
            throw UpdateCheckException.Decode(e)
        }
    }

    /**
     * 拉取 GitHub Releases 的「最新正式版」并归一为 [UpdateManifest]。
     *
     * 规则：
     * - `draft` / `prerelease` 直接失败（[UpdateCheckException.Empty]），预发布版绝不推给普通用户；
     * - `versionName` = `tag_name` 去掉前缀 `v`；`versionCode` 由语义化版本推导（见 [versionCodeFromTag]）；
     * - `apkUrl` 只取 `name` 以 `.apk` 结尾的资产（忽略大小写），挑不到即失败，不退回任意文件；
     * - `updateNote` = release `body`（Markdown 原样，UI 按纯文本展示），`force = false`。
     *
     * 失败语义与 [fetchManifest] 对齐：非 2xx → [UpdateCheckException.Http]；网络层 →
     * [UpdateCheckException.Network]；JSON 不符合模型 → [UpdateCheckException.Decode]；
     * 空/无正式资产 → [UpdateCheckException.Empty]。
     *
     * 例外：`403/429`（未认证 API 配额被吃光）时**自动回落**到不限流的
     * [fetchFromGitHubHtml]；回落也失败才抛出原始 403/429。其它状态码不回落。
     */
    suspend fun fetchFromGitHub(url: String = GITHUB_LATEST_RELEASE_URL): UpdateManifest =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url)
                // GitHub 要求 UA；Accept 显式走 v3 REST 媒体类型。
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .build()
            try {
                httpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw UpdateCheckException.Http(resp.code)
                    val body = resp.body?.string().orEmpty()
                    if (body.isBlank()) throw UpdateCheckException.Empty
                    // 显式传 serializer：避免依赖 reified 扩展的导入路径。
                    val release = NeteaseJson.decodeFromString(GitHubRelease.serializer(), body)
                    // 草稿 / 预发布不推给普通用户。
                    if (release.draft || release.prerelease) throw UpdateCheckException.Empty
                    val apkUrl = release.assets
                        .firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                        ?.browser_download_url
                        .orEmpty()
                    if (apkUrl.isBlank()) throw UpdateCheckException.Empty
                    UpdateManifest(
                        versionCode = versionCodeFromTag(release.tag_name),
                        versionName = versionNameFromTag(release.tag_name),
                        apkUrl = apkUrl,
                        updateNote = release.body,
                        force = false,
                    )
                }
            } catch (e: UpdateCheckException.Http) {
                // API 未认证配额按出口 IP 计（CGNAT 下多人共用同一出口 IP，额度极易
                // 被他人吃光）：403/429 时回落到「不消耗配额」的网页重定向推导；
                // 回落也失败才把原始 403/429 抛出去。其它状态码（如 404 仓库不存在）
                // 不回落——回落同样拿不到数据。
                if (e.code != 403 && e.code != 429) throw e
                try {
                    fetchFromGitHubHtml()
                } catch (fallback: CancellationException) {
                    throw fallback
                } catch (fallback: Exception) {
                    throw e
                }
            } catch (e: UpdateCheckException) {
                throw e
            } catch (e: CancellationException) {
                // 协程取消原样重抛。
                throw e
            } catch (e: IOException) {
                throw UpdateCheckException.Network(e)
            } catch (e: Exception) {
                throw UpdateCheckException.Decode(e)
            }
        }

    /**
     * API 限流（403/429）时的兜底：用网页端 `releases/latest` 的 302 推导最新正式版。
     *
     * 该路径是**普通网页请求**，不消耗 GitHub API 配额（API 额度按出口 IP 计，
     * CGNAT 下常被共享用户吃光），因此作为 [fetchFromGitHub] 的回落。
     *
     * 实现要点：
     * - 用 [OkHttpClient.newBuilder] 派生一个**不跟随重定向**的客户端，只读 `Location`
     *   头。原 client 与其连接池不受影响，`UpdateRepository.downloadApk` 使用的
     *   「跟随重定向」实例也不会被污染（下载必须继续跟随 302）。
     * - `Location` 形如 `https://github.com/Robincheng34/harken/releases/tag/v1.0.1`，
     *   取其 `/releases/tag/` 之后（去掉 query / 末尾斜杠）的一段作为 tag。
     * - **资产命名约定**：`{releases/download}/{tag}/Harken-{versionName}.apk`
     *   （当前 release 资产即 `Harken-1.0.0.apk`，已实测 302 可达）。
     *   ⚠️ 以后若修改 Release 资产命名，**必须同步修改本函数**，否则兜底路径会 404。
     * - 网页端拿不到发布说明，`updateNote` 为空字符串（UI 已容忍空）。
     */
    suspend fun fetchFromGitHubHtml(url: String = GITHUB_LATEST_RELEASE_WEB_URL): UpdateManifest =
        withContext(Dispatchers.IO) {
            // 派生实例：关闭跟随重定向，才能读到 302 的 Location。
            val client = httpClient.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            val request = Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .build()
            try {
                client.newCall(request).execute().use { resp ->
                    if (resp.code !in 300..399) {
                        // 200（无重定向，拿不到 tag）或其它失败：按既有错误族归。
                        if (!resp.isSuccessful) throw UpdateCheckException.Http(resp.code)
                        throw UpdateCheckException.Empty
                    }
                    val location = resp.header("Location").orEmpty().trim()
                    val tag = location.substringAfter("/releases/tag/", "")
                        .substringBefore('?')
                        .trim()
                        .trimEnd('/')
                    if (tag.isBlank()) throw UpdateCheckException.Empty
                    val versionName = versionNameFromTag(tag)
                    val versionCode = versionCodeFromTag(tag)
                    // tag 不是语义化版本（如 nightly）→ 视为无可用更新。
                    if (versionCode <= 0) throw UpdateCheckException.Empty
                    UpdateManifest(
                        versionCode = versionCode,
                        versionName = versionName,
                        apkUrl = "$GITHUB_RELEASES_DOWNLOAD_BASE/$tag/Harken-$versionName.apk",
                        updateNote = "",
                        force = false,
                    )
                }
            } catch (e: UpdateCheckException) {
                throw e
            } catch (e: CancellationException) {
                // 协程取消原样重抛。
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

        /** GitHub API 要求请求带 User-Agent（下载 APK 时同样复用）。 */
        internal const val USER_AGENT = "Harken-UpdateChecker"

        /** 默认更新源：GitHub Releases 最新正式版（公开仓库，无需 token）。 */
        const val GITHUB_LATEST_RELEASE_URL =
            "https://api.github.com/repos/Robincheng34/harken/releases/latest"

        /**
         * API 限流兜底用的**网页**地址：302 → `/releases/tag/{tag}`，
         * 普通网页请求，不消耗 GitHub API 配额。
         */
        const val GITHUB_LATEST_RELEASE_WEB_URL =
            "https://github.com/Robincheng34/harken/releases/latest"

        /**
         * 资产下载地址前缀。完整约定见 [fetchFromGitHubHtml] 的 KDoc：
         * `{前缀}/{tag}/Harken-{versionName}.apk`。
         */
        private const val GITHUB_RELEASES_DOWNLOAD_BASE =
            "https://github.com/Robincheng34/harken/releases/download"

        /**
         * 默认清单地址（兼容旧名）：现指向 GitHub Releases。
         *
         * 用户在设置里填自定义地址时仍走 [fetchManifest] 的自托管 JSON 解析。
         */
        const val UPDATE_MANIFEST_URL = GITHUB_LATEST_RELEASE_URL

        /** 是否 GitHub API 形态的更新源（据此选择 Releases 解析器）。 */
        fun isGitHubReleaseUrl(url: String): Boolean =
            url.trim().startsWith("https://api.github.com/", ignoreCase = true)

        /**
         * 语义化版本 → versionCode：`major * 10000 + minor * 100 + patch`。
         *
         * 例：`v1.0.0` → 10000；`v1.0.1` → 10001；`v1.2` → 10200（缺省段按 0）。
         * 约定 minor / patch < 100（保证不跨位）；major 解析失败或结果溢出 → 0
         * （上层据此不会误判为有更新）。
         */
        internal fun versionCodeFromTag(tag: String): Int {
            val parts = versionNameFromTag(tag).split('.')
            val major = parts.getOrNull(0)?.toIntOrNull() ?: return 0
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
            if (major < 0 || minor < 0 || patch < 0) return 0
            val code = major.toLong() * 10_000L + minor.toLong() * 100L + patch.toLong()
            return if (code >= 0L && code <= Int.MAX_VALUE.toLong()) code.toInt() else 0
        }

        /** `tag_name` → 展示用版本号（去掉前缀 `v` / `V` 与首尾空白）。 */
        internal fun versionNameFromTag(tag: String): String =
            tag.trim().removePrefix("v").removePrefix("V")
    }
}
