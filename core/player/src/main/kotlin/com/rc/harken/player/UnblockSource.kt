// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken.player

import com.rc.harken.net.model.Track

/**
 * 第三方解锁候选及其元数据（iOS `AudioSourceProvider.AudioCandidate`）。
 *
 * iOS 对第三方源做「合理匹配」校验（时长差 ≤ 5s、曲名一致、歌手命中）后才采纳，
 * 防止解锁服务返回错歌。`UnblockSource` 的默认实现可只给 URL（跳过校验），
 * 能提供元数据的实现应改用 [UnblockSource.resolveCandidate]。
 */
data class UnblockCandidate(
    val url: String,
    val title: String,
    val artist: String,
    val durationMS: Long,
    /**
     * 音源展示名（iOS `ResolvedAudioSource.displayName`，见
     * `AudioSourceProvider.swift:19`；取值如「酷狗音乐」「酷我音乐」）。
     *
     * 用于 iOS `PlayerService.swift:1094-1095` 的「已使用第三方音源：X」提示——
     * 用户开了灰色解锁就必须知道实际音源是谁。
     *
     * 默认 null：只校验 URL 的宽松路径（[UnblockSource.resolve]）拿不到元数据，
     * 此时解析器按 [UNBLOCK_SOURCE_UNNAMED] 处理，提示退化为不带名字的形式
     * ——但**仍然会提示**，因为「是否走第三方源」这件事本身比「源叫什么」更重要。
     */
    val sourceName: String? = null,
)

/**
 * 灰色歌曲解锁钩子（P1 预留，默认关闭）。
 *
 * 架构文档 §9 假设 1：解锁有法律风险，v1.0 仅保留接口 + 设置开关（默认关），
 * 并保留「客户书面确认后启用」的合规流程。
 */
interface UnblockSource {
    /** 返回第三方直链；null 表示无法解锁。 */
    suspend fun resolve(track: Track): String?

    /**
     * 返回带元数据的候选（iOS `isPlausibleMatch` 校验所需）。
     *
     * 默认返回 null → 解析器退回仅校验 URL 的宽松路径。
     */
    suspend fun resolveCandidate(track: Track): UnblockCandidate? = null
}

/** iOS `AudioSourceProvider.isPlausibleMatch` 的时长容差（毫秒）。 */
internal const val DURATION_TOLERANCE_MS = 10_000L

/**
 * 解锁命中但源名未知的占位值。
 *
 * iOS 侧第三方源**必然**带 `displayName`（`AudioSourceProvider.swift:19`），
 * 不存在这种情况；Android 的宽松路径 [UnblockSource.resolve] 只返回 URL，没有
 * 元数据。这里用「**非空**」表示「命中了解锁」（决定要不要提示用户），用
 * 「空串」表示「源名未知」（决定提示文案带不带名字）。写成常量是为了让这个
 * 约定在代码里只出现一次。
 */
const val UNBLOCK_SOURCE_UNNAMED: String = ""

/**
 * 时长合理性校验（iOS `isPlausibleMatch` 的时长维度）。
 *
 * 规则：曲目自身时长与候选时长都必须为正，且差值不超过 [DURATION_TOLERANCE_MS]。
 * 曲名/歌手维度需要归一化策略（P1 接入真实第三方源时再补），当前只做时长——
 * 这正是「时长异常拒绝」要覆盖的场景。
 */
internal fun isPlausibleDuration(track: Track, candidate: UnblockCandidate): Boolean {
    if (track.durationMS <= 0L) return false
    if (candidate.durationMS <= 0L) return false
    return kotlin.math.abs(track.durationMS - candidate.durationMS) <= DURATION_TOLERANCE_MS
}