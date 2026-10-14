// Ported from kumone by missuo (LGPL-3.0)
//
// 设置页 UI 状态（T28 `SettingsUiState.kt`）。
//
// 直接由 `SettingsRepository.settings` 派生，附加登录态与登出动作态。

package im.missuo.kumone.feature.settings

import im.missuo.kumone.playback.AudioQuality
import im.missuo.kumone.storage.settings.AppAppearance
import im.missuo.kumone.storage.settings.AppSettings
import im.missuo.kumone.storage.settings.LyricsAnnotation
import im.missuo.kumone.storage.settings.NowPlayingMode

/** 设置页 UI 状态。 */
data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val isLoggedIn: Boolean = false,
    val nickname: String = "",
    val isLoggingOut: Boolean = false,
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
