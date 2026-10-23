// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 音质解析与回落单测（P2-3 `SongUrlResolverTest.kt`，纯 JVM）。
//
// 依赖经 `SongUrlApi` / `UnblockPreference` 窄接口注入伪实现，逐条对齐 iOS
// `resolveSource(for:)`（PlayerService.swift:1004-1041）的行为判定：
//  1. 首选音质命中（:1006）；
//  2. 仅 `url == nil` 才回退 standard 一级（:1007）——**与 freeTrialInfo 无关**；
//  3. `freeTrialInfo != null` 试听片段**照播并打 isTrial 标**，不降级不拒绝（:1039）；
//  4. 解锁触发条件为 `url == nil || isTrial` 且开关打开（:1018），成功采纳后 isTrial=false；
//  5. 完整音源绝不触发解锁（:1018 反面）；
//  6. `code != 200` 拒绝（伪实现抛 Business）；
//  7. http → https 强制升级（:1011-1012）；
//  8. 时长异常拒绝（第三方解锁源返回错歌，>5s 差值）。

package com.rc.harken.player

import com.rc.harken.net.api.NeteaseApiError
import com.rc.harken.net.model.FreeTrialInfo
import com.rc.harken.net.model.SongURLData
import com.rc.harken.net.model.Track
import com.rc.harken.playback.AudioQuality
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SongUrlResolverTest {

    /** 曲目默认 200_000ms，便于验证时长容差。 */
    private val track = Track(id = 1, name = "Song", durationMS = 200_000)

    /** 按 level 返回预置响应的伪 API；level 缺失即返回空列表。 */
    private class FakeApi(private val byLevel: Map<String, SongURLData>) : SongUrlApi {
        var calls = 0
        val requestedLevels = mutableListOf<String>()

        override suspend fun songURL(ids: List<Long>, level: String): List<SongURLData> {
            calls += 1
            requestedLevels += level
            return byLevel[level]?.let { listOf(it) } ?: emptyList()
        }
    }

    private fun data(
        url: String? = "https://cdn.test/song.m4a",
        level: String? = null,
        trial: Boolean = false,
        time: Long = 0,
        code: Int? = 200,
    ) = SongURLData(
        id = 1,
        url = url,
        level = level,
        br = 320_000,
        size = 1_024L,
        type = "mp3",
        fee = if (trial) 1 else 0,
        freeTrialInfo = if (trial) FreeTrialInfo(start = 0, end = 30_000) else null,
        time = time,
        code = code,
        md5 = "abc",
    )

    private fun resolver(
        api: SongUrlApi,
        unblockEnabled: Boolean = false,
        unblock: UnblockSource? = null,
    ) = SongUrlResolver(
        api = api,
        unblockPreference = object : UnblockPreference {
            override suspend fun isUnblockEnabled(): Boolean = unblockEnabled
        },
        unblock = unblock,
    )

    // MARK: - 1. 首选音质命中

    @Test
    fun `preferred quality hit returns without downgrade`() = runTest {
        val api = FakeApi(mapOf("exhigh" to data(url = "https://cdn.test/a.m4a", level = "exhigh")))
        val resolved = resolver(api).resolve(track, AudioQuality.EXHIGH)

        assertEquals("https://cdn.test/a.m4a", resolved.url)
        assertEquals("exhigh", resolved.level)
        assertFalse(resolved.downgraded)
        assertEquals(listOf("exhigh"), api.requestedLevels)
    }

    // MARK: - 2. sky → standard 一级回落

    @Test
    fun `null url at preferred level falls back to standard once`() = runTest {
        val api = FakeApi(
            mapOf(
                "hires" to data(url = null, level = "hires"),
                "standard" to data(url = "https://cdn.test/std.m4a", level = "standard"),
            ),
        )
        val resolved = resolver(api).resolve(track, AudioQuality.HIRES)

        assertEquals("https://cdn.test/std.m4a", resolved.url)
        assertTrue(resolved.downgraded)
        assertEquals(listOf("hires", "standard"), api.requestedLevels)
    }

    @Test
    fun `no downgrade when preferred level is already standard`() = runTest {
        // standard 已是最低档：不应再次请求（避免无谓网络往返）。
        // iOS PlayerService.swift:1007 的回落条件是 `level != "standard"`——
        // 首选档位本身就是 standard 时整条回落分支不进入，因此**有且仅有
        // 一次**网络请求；随后 url == nil 直接抛错（无音源）。
        val api = FakeApi(mapOf("standard" to data(url = null, level = "standard")))
        val failure = runCatching { resolver(api).resolve(track, AudioQuality.STANDARD) }

        assertEquals(listOf("standard"), api.requestedLevels)
        assertEquals(1, api.calls)
        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull() is NeteaseApiError.Business)
    }

    // MARK: - 3. 试听片段：照播 + 打标（不降级、不拒绝）

    @Test
    fun `trial response is accepted and flagged isTrial`() = runTest {
        // iOS PlayerService.swift:1039 `isTrial: data?.freeTrialInfo != nil`：
        // 试听片段是**可播放音源**，URL 照常采用，只打标记——不降级、不拒绝。
        val api = FakeApi(
            mapOf(
                "lossless" to data(url = "https://cdn.test/trial.m4a", level = "lossless", trial = true),
            ),
        )
        val resolved = resolver(api).resolve(track, AudioQuality.LOSSLESS)

        assertEquals("https://cdn.test/trial.m4a", resolved.url)
        assertTrue(resolved.isTrial)
        // 关键：试听**不触发降级**，因此 standard 档从未被请求（iOS 1007 只看 url==nil）。
        assertEquals(listOf("lossless"), api.requestedLevels)
    }

    @Test
    fun `empty url at preferred level falls back to standard even when response is trial`() = runTest {
        // iOS 1007 `if data?.url == nil` → 降级；1007 的条件与 freeTrialInfo 无关。
        // 此处首选档「无 URL 且带 freeTrialInfo」，仍应回退 standard 拿完整音源。
        val api = FakeApi(
            mapOf(
                "hires" to data(url = null, level = "hires", trial = true),
                "standard" to data(url = "https://cdn.test/full.m4a", level = "standard"),
            ),
        )
        val resolved = resolver(api).resolve(track, AudioQuality.HIRES)

        assertEquals(listOf("hires", "standard"), api.requestedLevels)
        assertEquals("https://cdn.test/full.m4a", resolved.url)
        assertTrue(resolved.downgraded)
        assertTrue(!resolved.isTrial)
    }

    // 注：原「试听回退到完整 standard」用例已按 iOS 语义重写为上面的
    // `empty url at preferred level falls back to standard even when response is trial`
    // ——iOS 只在 `url == nil` 时降级，试听（URL 有效）不降级。

    // MARK: - 4. code != 200 拒绝

    @Test
    fun `business error at every level is rejected`() = runTest {
        // 伪实现：所有 level 都抛 Business（等价 code != 200）。
        val failing = object : SongUrlApi {
            override suspend fun songURL(ids: List<Long>, level: String): List<SongURLData> =
                throw NeteaseApiError.Business(403, "无版权")
        }
        val error = runCatching {
            resolver(failing).resolve(track, AudioQuality.EXHIGH)
        }.exceptionOrNull()

        assertTrue(error is NeteaseApiError.Business)
    }

    // MARK: - 5. http → https 强制升级

    @Test
    fun `http url is upgraded to https`() = runTest {
        val api = FakeApi(
            mapOf("exhigh" to data(url = "http://cdn.test/plain.m4a", level = "exhigh")),
        )
        val resolved = resolver(api).resolve(track, AudioQuality.EXHIGH)

        assertEquals("https://cdn.test/plain.m4a", resolved.url)
        assertFalse(resolved.url.startsWith("http://"))
    }

    // MARK: - 6. 时长异常拒绝（第三方源返回错歌）

    @Test
    fun `unblock candidate with mismatched duration is rejected`() = runTest {
        val empty = object : SongUrlApi {
            override suspend fun songURL(ids: List<Long>, level: String): List<SongURLData> = emptyList()
        }
        val wrongSong = object : UnblockSource {
            override suspend fun resolve(track: Track): String? = "https://third.test/wrong.m4a"
            override suspend fun resolveCandidate(track: Track): UnblockCandidate =
                UnblockCandidate(
                    url = "https://third.test/wrong.m4a",
                    title = track.name,
                    artist = "x",
                    durationMS = track.durationMS + 30_000, // 差 30s > 5s 容差
                )
        }
        val error = runCatching {
            resolver(empty, unblockEnabled = true, unblock = wrongSong)
                .resolve(track, AudioQuality.EXHIGH)
        }.exceptionOrNull()

        assertTrue(error is NeteaseApiError.Business)
    }

    @Test
    fun `unblock candidate within duration tolerance is accepted`() = runTest {
        val empty = object : SongUrlApi {
            override suspend fun songURL(ids: List<Long>, level: String): List<SongURLData> = emptyList()
        }
        val good = object : UnblockSource {
            override suspend fun resolve(track: Track): String? = null
            override suspend fun resolveCandidate(track: Track): UnblockCandidate =
                UnblockCandidate(
                    url = "http://third.test/good.m4a",
                    title = track.name,
                    artist = "x",
                    durationMS = track.durationMS + 1_000, // 差 1s，在容差内
                    sourceName = "酷狗音乐", // iOS AudioSourceProvider 的 displayName
                )
        }
        val resolved = resolver(empty, unblockEnabled = true, unblock = good)
            .resolve(track, AudioQuality.EXHIGH)

        assertEquals("https://third.test/good.m4a", resolved.url) // 顺带 http→https
        assertTrue(resolved.downgraded)
        // iOS :1026 `unblock = unblocked.displayName`：候选带源名时必须透传，
        // UI 据此弹「已使用第三方音源：酷狗音乐」。
        assertEquals("酷狗音乐", resolved.unblockSource)
    }

    @Test
    fun `unblock without source metadata still flags as unblocked`() = runTest {
        // 宽松路径 [UnblockSource.resolve] 只有 URL、没有元数据：iOS 侧不存在
        // 这种情况（第三方源必带 displayName），安卓侧退化为 UNBLOCK_SOURCE_UNNAMED
        // ——仍然要提示用户走了第三方源，只是不带名字。
        val empty = object : SongUrlApi {
            override suspend fun songURL(ids: List<Long>, level: String): List<SongURLData> = emptyList()
        }
        val unnamed = object : UnblockSource {
            override suspend fun resolve(track: Track): String? = "https://third.test/anon.m4a"
        }
        val resolved = resolver(empty, unblockEnabled = true, unblock = unnamed)
            .resolve(track, AudioQuality.EXHIGH)

        assertEquals("https://third.test/anon.m4a", resolved.url)
        assertEquals(UNBLOCK_SOURCE_UNNAMED, resolved.unblockSource)
        assertNotNull("非空即代表命中解锁，UI 据此决定弹提示", resolved.unblockSource)
    }

    @Test
    fun `unblock rescues a trial response when toggle is on`() = runTest {
        // iOS 1018：`url == nil || freeTrialInfo != nil` 两支都走解锁尝试。
        // 这里首选档返回「试听片段且 URL 有效」——URL 非空故不降级，但因是试听仍触发解锁。
        val api = FakeApi(
            mapOf("hires" to data(url = "https://cdn.test/trial.m4a", level = "hires", trial = true)),
        )
        val source = object : UnblockSource {
            override suspend fun resolve(track: Track): String? = "https://third.test/full.m4a"
        }
        val resolved = resolver(api, unblockEnabled = true, unblock = source)
            .resolve(track, AudioQuality.HIRES)

        assertEquals("https://third.test/full.m4a", resolved.url)
        assertTrue(resolved.downgraded)
        assertTrue("解锁救援后不再是试听", !resolved.isTrial)
        // 试听不触发降级，因此 standard 未被请求。
        assertEquals(listOf("hires"), api.requestedLevels)
        // 该用例走的是宽松路径（只有 URL），源名未知 → UNBLOCK_SOURCE_UNNAMED。
        assertEquals(UNBLOCK_SOURCE_UNNAMED, resolved.unblockSource)
    }

    @Test
    fun `full track response never triggers unblock`() = runTest {
        val api = FakeApi(
            mapOf("hires" to data(url = "https://cdn.test/full.m4a", level = "hires")),
        )
        var called = false
        val source = object : UnblockSource {
            override suspend fun resolve(track: Track): String? {
                called = true
                return "https://third.test/x.m4a"
            }
        }
        val resolved = resolver(api, unblockEnabled = true, unblock = source)
            .resolve(track, AudioQuality.HIRES)

        assertEquals("https://cdn.test/full.m4a", resolved.url)
        assertFalse("完整音源不应触发解锁", called)
        // iOS :1094 的反面：没走解锁就不能留着源名，否则下一首走官方源时
        // 状态里仍挂着「已使用第三方音源」。
        assertNull(resolved.unblockSource)
    }

    @Test
    fun `unblock is skipped when toggle is off`() = runTest {
        val empty = object : SongUrlApi {
            override suspend fun songURL(ids: List<Long>, level: String): List<SongURLData> = emptyList()
        }
        var called = false
        val source = object : UnblockSource {
            override suspend fun resolve(track: Track): String? {
                called = true
                return "https://third.test/x.m4a"
            }
        }
        val error = runCatching {
            resolver(empty, unblockEnabled = false, unblock = source)
                .resolve(track, AudioQuality.EXHIGH)
        }.exceptionOrNull()

        assertTrue(error is NeteaseApiError.Business)
        assertFalse("解锁开关默认关，不应调用解锁源", called)
    }
}