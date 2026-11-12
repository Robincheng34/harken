// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 设置页 UI 状态（T28 `SettingsUiState.kt`）。
//
// 直接由 `SettingsRepository.settings` 派生，附加登录态与登出动作态。

package com.rc.harken.feature.settings

import com.rc.harken.playback.AudioQuality
import com.rc.harken.storage.settings.AppAppearance
import com.rc.harken.storage.settings.AppSettings
import com.rc.harken.storage.settings.LyricsAnnotation
import com.rc.harken.storage.settings.NowPlayingMode

/** 退出登录失败原因（供 UI 选择提示文案，文案真源在 `strings.xml`）。 */
enum class LogoutFailure {
    /** 设备当前离线：不发请求，直接失败（避免本地 cookie 被清而服务端会话仍在）。 */
    OFFLINE,

    /** 请求发出但失败。 */
    SERVER,
}

/** 设置页 UI 状态。 */
data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val isLoggedIn: Boolean = false,
    val nickname: String = "",
    val isLoggingOut: Boolean = false,
    val updateCheck: UpdateCheckUi = UpdateCheckUi.Idle,
    val updateSourceUrl: String = "",
    /**
     * 最近一次退出登录的失败原因（成功或下次尝试时清空）。
     *
     * 不复用 [updateCheck]：那是「检查更新」流程的状态，塞退出登录错误会让
     * 「关于」分组显示一条语义错误的提示，且「检查更新」逻辑会误清它。
     */
    val logoutFailure: LogoutFailure? = null,
    /**
     * 退出登录失败次数（自增）。
     *
     * 作为 UI 触发提示的信号量：同一原因连续失败时 [logoutFailure] 值不变，
     * 仅靠它无法让 Composable 再次弹出提示。
     */
    val logoutFailureSeq: Int = 0,
)

/** 音质可选项（对应 `AudioQuality`，自高到低）。 */
val AudioQualityOptions: List<AudioQuality> = listOf(
    AudioQuality.STANDARD,
    AudioQuality.HIGHER,
    AudioQuality.EXHIGH,
    AudioQuality.LOSSLESS,
    AudioQuality.HIRES,
)

/** 外观可选项。 */
val AppearanceOptions: List<AppAppearance> = listOf(
    AppAppearance.LIGHT,
    AppAppearance.DARK,
    AppAppearance.AUTO,
)

/** 播放页样式可选项（iOS v1.0 仅开放黑胶 / 沉浸，见 `NowPlayingMode` 注释）。 */
val NowPlayingModeOptions: List<NowPlayingMode> = listOf(
    NowPlayingMode.VINYL,
    NowPlayingMode.IMMERSIVE,
)

/** 歌词注音可选项。 */
val LyricsAnnotationOptions: List<LyricsAnnotation> = LyricsAnnotation.entries.toList()
