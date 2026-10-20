// Ported from kumone by missuo (LGPL-3.0)
//
// 在线检测更新的 UI 状态（feature/settings 内部定义，复用 core:net 的 [UpdateManifest]）。

package im.missuo.kumone.feature.settings

import im.missuo.kumone.net.update.UpdateManifest

/**
 * 设置页「检查更新」相关的 UI 状态。
 *
 * 状态单一来源为 [SettingsViewModel] 内的 [SettingsUiState.updateCheck]；
 * 下载进度也经由 ViewModel 回写，避免 Screen 直接改动内部 StateFlow。
 */
sealed interface UpdateCheckUi {
    /** 空闲（未检查 / 已收起提示）。 */
    object Idle : UpdateCheckUi

    /** 正在拉取版本清单。 */
    object Checking : UpdateCheckUi

    /** 已是最新。 */
    data class Latest(val versionName: String) : UpdateCheckUi

    /** 发现新版本（携带待安装的清单）。 */
    data class Available(val manifest: UpdateManifest) : UpdateCheckUi

    /** 检查 / 下载失败。 */
    data class Error(val message: String) : UpdateCheckUi

    /** 下载中（fraction ∈ [0,1]）。 */
    data class Downloading(val fraction: Float) : UpdateCheckUi
}
