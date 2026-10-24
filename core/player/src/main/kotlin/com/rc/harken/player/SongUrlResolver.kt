// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken.player

import com.rc.harken.net.api.NeteaseApi
import com.rc.harken.net.api.NeteaseApiError
import com.rc.harken.net.model.SongURLData
import com.rc.harken.net.model.Track
import com.rc.harken.playback.AudioQuality
import com.rc.harken.storage.download.LocalSongSource
import com.rc.harken.storage.settings.SettingsRepository
import kotlinx.coroutines.CancellationException

/** 一次解析的播放源（架构文档 §4.1 `ResolvedSongUrl`）。 */
data class ResolvedSongUrl(
    val url: String,
    val level: String,
    val downgraded: Boolean,
    val isTrial: Boolean,
    /**
     * 命中的第三方音源展示名（iOS `ResolvedSource.unblockSource`，
     * `PlayerService.swift:1026` `unblock = unblocked.displayName` → `:1038`）。
     *
     * **非空即代表本次播放走的是第三方解锁源**，UI 据此弹「已使用第三方音源：X」
     * （iOS `:1094-1095`）。null = 官方源。空串见 [UNBLOCK_SOURCE_UNNAMED]。
     */
    val unblockSource: String? = null,
)

/**
 * 音质解析与回退（iOS `resolveSource(for:)`，PlayerService.swift:1004）。
 *
 * 1:1 对齐 iOS `resolveSource(for:)`（PlayerService.swift:1004-1041）：
 * 1. 按用户首选档请求；
 * 2. **仅当 `url` 为空**且首选档不是 [AudioQuality.STANDARD] 时，回退 [AudioQuality.STANDARD]
 *    一级（iOS 只做一级而非逐级阶梯，以 iOS 源码为准；`downgraded` 记录实际服务档与
 *    首选档是否不同）；
 * 3. `http:` 升级 `https:`；
 * 4. 若 `url` 仍为空**或**响应为试听片段（`freeTrialInfo != null`）且用户开启了灰色
 *    解锁（默认关），走 [UnblockSource] 钩子救援（iOS PlayerService.swift:1018）；
 * 5. 仍拿不到可用 URL 才抛 [NeteaseApiError.Business]，由引擎计入连续失败。
 *
 * **试听片段语义（iOS PlayerService.swift:1039/1097-1098/1103/1111/1152）**：
 * 试听片段是**可播放音源**，不是错误。iOS 会照常播放并置 `isTrial=true`，向 UI 暴露
 * 「VIP 歌曲，当前为试听片段」提示，且 `currentCacheKey = resolved.isTrial ? nil : key`
 * ——试听不进缓存（镜像写入临时 `.part` 文件），自动进下一曲也跳过（`autoAdvanceTarget`
 * 的 `guard !isTrial`）。因此本实现**不得**因 `freeTrialInfo != null` 而拒绝播放或降级；
 * 只有「下载 / 预取」路径才要求 `freeTrialInfo == nil`（iOS `NeteaseAPI.swift:440`、
 * `QueueOrderSelector.swift:625`、`QueuePrefetcher.swift:216`），该约束在下载侧实现，
 * 不在本解析器内。
 *
 * 依赖收窄为 [SongUrlApi] + [UnblockPreference] 两个窄接口，使本链路可纯 JVM 单测
 * （见 `SongUrlResolverTest`）；生产构造见 [SongUrlResolver] 的便捷工厂。
 */
class SongUrlResolver(
    private val api: SongUrlApi,
    private val unblockPreference: UnblockPreference,
    private val unblock: UnblockSource? = null,
    private val localSourceProvider: LocalSongSource? = null,
) {

    /** 由真实依赖构造（引擎侧使用）。 */
    constructor(
        api: NeteaseApi,
        settings: SettingsRepository,
        unblock: UnblockSource? = null,
        localSourceProvider: LocalSongSource? = null,
    ) : this(api.asSongUrlApi(), settings.asUnblockPreference(), unblock, localSourceProvider)

    suspend fun resolve(track: Track, preferred: AudioQuality): ResolvedSongUrl {
        val preferredLevel = preferred.apiLevel

        // 离线优先：命中本地文件直接返回 file:// URI，零改 ExoPlayer 即可离线播放。
        val local = localSourceProvider?.localUri(track.id, preferredLevel)
            ?: localSourceProvider?.localUriAny(track.id)
        if (!local.isNullOrBlank()) {
            return ResolvedSongUrl(
                url = local,
                level = preferredLevel,
                downgraded = false,
                isTrial = false,
            )
        }

        // 1) 首选档。
        var data = fetch(track.id, preferredLevel)
        // 2) URL 为空 → 回退 standard 一级（iOS 1007 `if data?.url == nil`）。
        //    注意：iOS 的降级条件**只看 url 是否为 nil**；`freeTrialInfo != null`
        //    那一半支走的是下面的解锁分支，不触发降级。
        if (data?.url.isNullOrEmpty() && preferredLevel != AudioQuality.STANDARD.apiLevel) {
            data = fetch(track.id, AudioQuality.STANDARD.apiLevel)
        }

        // 3) URL 为空**或**仅为试听 → 尝试灰色解锁（iOS 1018
        //    `if url == nil || data?.freeTrialInfo != nil, enableUnblock`）。
        //    括号不可省：iOS 的 enableUnblock 同时门控两个分支，而 `&&` 优先级高于
        //    `||`，漏写括号会让「URL 为空」绕过开关直接解锁。
        val needsSource = data?.url.isNullOrEmpty() || data.isTrial()
        if (needsSource && unblockPreference.isUnblockEnabled()) {
            val rescued = rescue(track)
            if (rescued != null) {
                return ResolvedSongUrl(
                    url = rescued.url.replace("http://", "https://"),
                    level = LEVEL_UNBLOCK,
                    downgraded = true,
                    // iOS :1027 `data = nil`：解锁采纳后原响应作废，试听标记随之清零。
                    isTrial = false,
                    // iOS :1026 `unblock = unblocked.displayName`；宽松路径无元数据
                    // 时退化为 [UNBLOCK_SOURCE_UNNAMED]（仍提示，只是不带名字）。
                    unblockSource = rescued.sourceName ?: UNBLOCK_SOURCE_UNNAMED,
                )
            }
        }

        // 4) 仍无 URL → 彻底失败。iOS 1030 `guard let url else { return nil }`。
        val rawURL = data?.url
        if (rawURL.isNullOrEmpty()) {
            throw NeteaseApiError.Business(ERR_NO_SOURCE, "无法获取播放地址")
        }
        // 5) 试听片段照播 + 打标（iOS 1039 `isTrial: data?.freeTrialInfo != nil`）。
        val servedLevel = data.level ?: preferredLevel
        return ResolvedSongUrl(
            url = rawURL.replace("http://", "https://"),
            level = servedLevel,
            downgraded = servedLevel != preferredLevel,
            isTrial = data.isTrial(),
        )
    }

    /**
     * 灰色解锁救援（iOS `UnblockService.resolve` 后的采纳判定）。
     *
     * 按 iOS `isPlausibleMatch` 校验时长（iOS 5s，本实现放宽到 10s，
     * 见 [DURATION_TOLERANCE_MS]），时长不合理即视为「解锁源返回错歌」而拒绝。
     */
    private suspend fun rescue(track: Track): RescueResult? {
        val source = unblock ?: return null
        val candidate = source.resolveCandidate(track) ?: return null
        if (!isPlausibleDuration(track, candidate)) return null
        return RescueResult(url = candidate.url, sourceName = candidate.sourceName)
    }

    /** 解锁救援结果：[sourceName] 为 null 表示源名未知。 */
    private data class RescueResult(val url: String, val sourceName: String?)

    private suspend fun fetch(trackID: Long, level: String): SongURLData? = try {
        api.songURL(listOf(trackID), level).firstOrNull()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        // 含 NeteaseApiError.Business(code != 200)：按「无音源」处理并回落。
        null
    }

    private companion object {
        const val LEVEL_UNBLOCK = "unblock"
        const val ERR_NO_SOURCE = -1
    }
}

/**
 * 是否为试听片段（iOS `data?.freeTrialInfo != nil`）。
 *
 * 试听片段的 URL **会被采用并播放**，只是打 `isTrial` 标记；它**不触发降级**
 * （iOS 1007 的降级条件只看 `url == nil`），也不进缓存（iOS 1103）。
 * 拒绝试听只发生在离线下载路径（iOS `NeteaseAPI.downloadResource`），安卓侧
 * 尚未实现离线下载，故此处无对应判定。
 */
private fun SongURLData?.isTrial(): Boolean = this?.freeTrialInfo != null