// GitHub Releases 更新检查的 JVM 单测（MockWebServer 本地模拟，完全离线）。
//
// 覆盖：
//  - Release JSON → UpdateManifest 映射（tag/版本号/body/资产下载地址）
//  - `.apk` 资产挑选语义、无 `.apk` 资产 → Empty
//  - draft / prerelease → Empty
//  - API 403 → 自动回落到网页 302（Location → tag → 约定 apkUrl）
//  - API 404 → 不回落（用请求次数证明）
//  - 语义化版本 → versionCode 编码边界
//
// 网络模拟方式：给 OkHttpClient 加一个「域名重写」拦截器——生产代码里的
// `https://api.github.com/...` 与 `https://github.com/...` 会被改写到本地
// MockWebServer（路径/查询串保持不变），因此测试跑的是**线上真实的 URL 与
// 请求链**，却完全不访问外网，也不需要为了可测性改动生产代码。

package com.rc.harken.net.update

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UpdateCheckerGitHubTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val base = server.url("/")
        client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            // 生产域名 → 本地 MockWebServer（host/port/scheme 改写，path 原样保留）。
            .addInterceptor { chain ->
                val request = chain.request()
                val host = request.url.host
                if (host == "api.github.com" || host == "github.com") {
                    val local = request.url.newBuilder()
                        .scheme(base.scheme)
                        .host(base.host)
                        .port(base.port)
                        .build()
                    chain.proceed(request.newBuilder().url(local).build())
                } else {
                    chain.proceed(request)
                }
            }
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── 测试夹具 ──────────────────────────────────────────────────────────

    private fun enqueueJson(json: String) {
        server.enqueue(MockResponse().setResponseCode(200).setBody(json))
    }

    /** 构造一个 GitHub `releases/latest` 响应；[assets] 为 (name, downloadUrl) 有序列表。 */
    private fun releaseJson(
        tag: String,
        body: String = "",
        assets: List<Pair<String, String>> = emptyList(),
        prerelease: Boolean = false,
        draft: Boolean = false,
    ): String {
        val assetsJson = assets.joinToString(separator = ",") { (name, url) ->
            """{"name":"$name","browser_download_url":"$url","size":123}"""
        }
        return """
            {
              "tag_name": "$tag",
              "name": "Harken $tag",
              "body": "$body",
              "prerelease": $prerelease,
              "draft": $draft,
              "assets": [$assetsJson]
            }
        """.trimIndent()
    }

    /** 按 path 分派：API 路径返回 [apiResponse]，网页 `releases/latest` 返回 302 + [location]。 */
    private fun dispatchGitHub(
        apiResponse: () -> MockResponse,
        location: String?,
    ) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    // API：/repos/{owner}/{repo}/releases/latest
                    path.startsWith("/repos/") -> apiResponse()
                    // 网页兜底：/{owner}/{repo}/releases/latest
                    path.endsWith("/releases/latest") && location != null ->
                        MockResponse().setResponseCode(302).addHeader("Location", location)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    // ── 1. 正常 Release → UpdateManifest ─────────────────────────────────

    @Test
    fun `normal release maps to manifest`() = runBlocking {
        enqueueJson(
            releaseJson(
                tag = "v1.0.1",
                body = "说明",
                assets = listOf(
                    "Harken-1.0.1.apk" to
                        "https://github.com/Robincheng34/harken/releases/download/v1.0.1/Harken-1.0.1.apk",
                ),
            ),
        )

        val manifest = UpdateChecker(client).fetchFromGitHub()

        assertEquals("1.0.1", manifest.versionName)
        assertEquals(10001, manifest.versionCode)
        assertEquals(
            "https://github.com/Robincheng34/harken/releases/download/v1.0.1/Harken-1.0.1.apk",
            manifest.apkUrl,
        )
        assertEquals("说明", manifest.updateNote)
        assertFalse(manifest.force)
    }

    // ── 2. `.apk` 资产挑选：取有序列表中第一个以 .apk 结尾的 ────────────

    @Test
    fun `picks the first asset whose name ends with dot apk`() = runBlocking {
        enqueueJson(
            releaseJson(
                tag = "v1.0.1",
                assets = listOf(
                    "foo.txt" to "https://example.com/foo.txt",
                    "bar.apk" to "https://example.com/bar.apk",
                    "Harken-1.0.1.apk" to "https://example.com/Harken-1.0.1.apk",
                ),
            ),
        )

        val manifest = UpdateChecker(client).fetchFromGitHub()

        // 实现语义：assets 顺序中第一个 name 以 ".apk" 结尾的资产（忽略大小写），
        // 即 bar.apk —— 不按 Harken-* 命名约定筛选。
        assertEquals("https://example.com/bar.apk", manifest.apkUrl)
        // 版本号仍来自 tag，与选中哪个资产无关。
        assertEquals(10001, manifest.versionCode)
        assertEquals("1.0.1", manifest.versionName)
    }

    // ── 3. 一个 .apk 都没有 → Empty ──────────────────────────────────────

    @Test
    fun `release without any apk asset fails with Empty`() = runBlocking {
        enqueueJson(
            releaseJson(
                tag = "v1.0.1",
                assets = listOf("foo.txt" to "https://example.com/foo.txt"),
            ),
        )

        val error = runCatching { UpdateChecker(client).fetchFromGitHub() }.exceptionOrNull()

        assertSame(UpdateCheckException.Empty, error)
    }

    // ── 4. 预发布 / 草稿 → Empty ────────────────────────────────────────

    @Test
    fun `prerelease and draft releases are ignored`() = runBlocking {
        val checker = UpdateChecker(client)

        enqueueJson(
            releaseJson(
                tag = "v1.0.2",
                assets = listOf("Harken-1.0.2.apk" to "https://example.com/a.apk"),
                prerelease = true,
            ),
        )
        assertSame(
            UpdateCheckException.Empty,
            runCatching { checker.fetchFromGitHub() }.exceptionOrNull(),
        )

        enqueueJson(
            releaseJson(
                tag = "v1.0.3",
                assets = listOf("Harken-1.0.3.apk" to "https://example.com/b.apk"),
                draft = true,
            ),
        )
        assertSame(
            UpdateCheckException.Empty,
            runCatching { checker.fetchFromGitHub() }.exceptionOrNull(),
        )
    }

    // ── 5. API 403 → 回落网页 302 ───────────────────────────────────────

    @Test
    fun `http 403 falls back to the html redirect`() = runBlocking {
        dispatchGitHub(
            apiResponse = {
                MockResponse().setResponseCode(403)
                    .setBody("""{"message":"API rate limit exceeded"}""")
            },
            location = "https://github.com/Robincheng34/harken/releases/tag/v1.0.2",
        )

        val manifest = UpdateChecker(client).fetchFromGitHub()

        assertEquals("1.0.2", manifest.versionName)
        assertEquals(10002, manifest.versionCode)
        // 兜底路径按资产命名约定推导（拿不到 assets 列表）。
        assertEquals(
            "https://github.com/Robincheng34/harken/releases/download/v1.0.2/Harken-1.0.2.apk",
            manifest.apkUrl,
        )
        assertEquals("", manifest.updateNote)
        assertFalse(manifest.force)
        // API 请求 + 回落请求，共 2 次——证明「先 403 再回落」这条链真的跑通了。
        assertEquals(2, server.requestCount)
    }

    // ── 6. API 404 → 不回落 ─────────────────────────────────────────────

    @Test
    fun `http 404 does not fall back`() = runBlocking {
        dispatchGitHub(
            apiResponse = {
                MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}""")
            },
            location = "https://github.com/Robincheng34/harken/releases/tag/v1.0.2",
        )

        val error = runCatching { UpdateChecker(client).fetchFromGitHub() }.exceptionOrNull()

        assertTrue(
            "expected UpdateCheckException.Http, got $error",
            error is UpdateCheckException.Http,
        )
        val httpError = error as UpdateCheckException.Http
        assertEquals(404, httpError.code)
        // 人话文案（设置页直接展示 e.message）。
        assertEquals("更新源不存在", httpError.message)
        // 只有 API 那一次请求：404 不触发回落。
        assertEquals(1, server.requestCount)
    }

    // ── 7. versionCode 编码边界 ─────────────────────────────────────────

    @Test
    fun `version code mapping boundaries`() {
        assertEquals(10000, UpdateChecker.versionCodeFromTag("v1.0.0"))
        assertEquals(20304, UpdateChecker.versionCodeFromTag("v2.3.4"))
        assertEquals(11000, UpdateChecker.versionCodeFromTag("v1.10.0"))
        assertEquals(10200, UpdateChecker.versionCodeFromTag("v1.2"))
        // 非语义化 tag → 0（上层不会误判为有更新）。
        assertEquals(0, UpdateChecker.versionCodeFromTag("nightly"))
        assertEquals(0, UpdateChecker.versionCodeFromTag(""))
        // versionName 仅去掉 v/V 前缀与空白。
        assertEquals("1.0.1", UpdateChecker.versionNameFromTag(" v1.0.1 "))
        assertEquals("1.0.1", UpdateChecker.versionNameFromTag("V1.0.1"))
    }

    // ── 8. 回落 tag 不可解析 → 回抛原始 403（回落失败语义）──────────────

    @Test
    fun `html fallback with an unparsable tag rethrows the original 403`() = runBlocking {
        dispatchGitHub(
            apiResponse = { MockResponse().setResponseCode(403) },
            location = "https://github.com/Robincheng34/harken/releases/tag/nightly",
        )

        val error = runCatching { UpdateChecker(client).fetchFromGitHub() }.exceptionOrNull()

        // 兜底拿不到语义化版本 → Empty → 内层吞掉并回抛原始 403。
        assertTrue(
            "expected UpdateCheckException.Http, got $error",
            error is UpdateCheckException.Http,
        )
        assertEquals(403, (error as UpdateCheckException.Http).code)
        assertEquals("请求过于频繁，请稍后再试", error.message)
        assertEquals(2, server.requestCount)
    }
}
