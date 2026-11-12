// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken.player

/**
 * 播放层上抛的一次性提示事件（iOS `ToastCenter.shared.show`）。
 *
 * @param messageRes `@StringRes` 资源 ID：本层不是 Composable，拿不到
 *        `stringResource`，也拿不到 `Context`（见 [MusicController] 的说明），
 *        故只上抛 ID，由 `:app` 根可组合解析成文案——既不固化语言，也让文案留在
 *        `strings.xml` 单一真源内。
 * @param args 文案的格式化参数（`context.getString(res, *args)`）。
 */
data class PlayerToastEvent(
    val messageRes: Int,
    val args: List<String> = emptyList(),
)

/**
 * 「按曲目一次性触发」提示的纯判定。
 *
 * 现在只服务**试听提示**（iOS `PlayerService.swift:1097-1099`
 * `ToastCenter.shared.show("VIP 歌曲，当前为试听片段")`）。
 *
 * 历史：iOS `:1093-1100` 有两条相互独立的 `if`（unblockSource / isTrial），
 * Android 侧原先也用本函数分别驱动「已使用第三方音源：X」与试听两条提示；
 * 前者已按需求整体移除（[MusicController] 不再读取 `unblockSource` 弹提示），
 * 但本函数原样保留——试听提示仍靠它，且它是纯函数、可 JVM 单测。
 *
 * 状态机规则：
 * - 条件不成立 → 清空记忆，不弹；
 * - 条件成立但拿不到曲目 ID → 保持记忆，不弹（没有可记忆的键）；
 * - 条件成立且曲目 ID 与上次相同 → 保持记忆，不弹（同一首不重复弹）；
 * - 条件成立且是新曲目 → 记忆切到新 ID，弹。
 *
 * iOS 本身**没有**这份「不重复弹」的记忆（它每次 `resolveAndLoad` 成功都会弹），
 * 安卓侧状态流会被 500ms 位置轮询反复推送，不记忆就会刷屏——这是有意的差异，
 * 只压制重复推送，不压制「换曲再弹」。
 *
 * 之所以抽成纯函数：真正订阅它的 [MusicController] 持有 `Context` 与 Media3
 * `MediaController`，JVM 单测里无法实例化。
 *
 * @param lastTrackID 上一次弹过该提示的曲目 ID（`null` 表示无记忆）。
 * @param trackID 当前曲目 ID（`PlayerState.currentTrack?.id`）。
 * @param isActive 该提示的条件是否成立（试听 = `isTrial`）。
 * @return `Pair(新的 lastTrackID, 本次是否弹)`。调用方需把 first 写回自己的记忆
 *         变量——函数本身无副作用，便于测试。
 */
fun shouldShowOneShotToast(
    lastTrackID: Long?,
    trackID: Long?,
    isActive: Boolean,
): Pair<Long?, Boolean> {
    if (!isActive) return null to false
    if (trackID == null) return lastTrackID to false
    if (trackID == lastTrackID) return lastTrackID to false
    return trackID to true
}
