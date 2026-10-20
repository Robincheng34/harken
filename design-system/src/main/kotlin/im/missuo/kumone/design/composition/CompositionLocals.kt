// Ported from kumone by missuo (LGPL-3.0)
//
// Composition-scoped ambient services shared across every feature module.
//
// NOTE (deviation from architecture §3 file list, which placed
// `ui/CompositionLocals.kt` under `:app`): feature modules must consume these
// locals but must NOT depend on `:app` (that would create a dependency cycle
// `:app -> :feature:* -> :app`). They are therefore hosted here in
// `:design-system`, the lowest shared module that already depends on all core
// layers. `:app` is responsible for providing the concrete values via
// `CompositionLocalProvider`.

package im.missuo.kumone.design.composition

import androidx.compose.runtime.CompositionLocal
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import im.missuo.kumone.design.component.ToastState
import im.missuo.kumone.net.api.NeteaseApi
import im.missuo.kumone.player.MusicController
import im.missuo.kumone.player.PlaybackCommands
import im.missuo.kumone.storage.download.DownloadRepository
import im.missuo.kumone.storage.search.SearchHistoryRepository
import im.missuo.kumone.storage.settings.SettingsRepository
import im.missuo.kumone.storage.update.UpdateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Process-wide music controller (playback engine bridge). */
val LocalMusicController: ProvidableCompositionLocal<MusicController?> = compositionLocalOf { null }

/** Netease API client (login, catalog, playback URL resolution, …). */
val LocalNeteaseApi: ProvidableCompositionLocal<NeteaseApi?> = compositionLocalOf { null }

/** Settings repository (audio quality, appearance, lyric preferences, …). */
val LocalSettingsRepository: ProvidableCompositionLocal<SettingsRepository?> = compositionLocalOf { null }

/** 在线检测更新仓储（拉取 version.json、下载/安装 APK、待更新持久化）。 */
val LocalUpdateRepository: ProvidableCompositionLocal<UpdateRepository?> = compositionLocalOf { null }

/** 搜索历史仓库（官方搜索页「搜索历史」）。 */
val LocalSearchHistoryRepository: ProvidableCompositionLocal<SearchHistoryRepository?> =
    compositionLocalOf { null }

/**
 * 播放指令集合（`PlaybackCommands`）：把「取数 → 成队列 → 播放」的编排从各
 * feature UI 收敛到 :core:player，经 :app 装配后由此下发。
 */
val LocalPlaybackCommands: ProvidableCompositionLocal<PlaybackCommands?> =
    compositionLocalOf { null }

/**
 * 下载仓储（离线播放闭环）：由 `:app` 提供 [im.missuo.kumone.storage.download.DownloadRepository]，
 * 各 feature 屏幕据此发起下载 / 读取已下载清单，无需反向依赖 `:app`。
 */
val LocalDownloadRepository: ProvidableCompositionLocal<DownloadRepository?> =
    compositionLocalOf { null }

/**
 * 登录成功后的刷新回调（由 `:app` 提供，触发 `AppGraph.bootstrap()` 写入登录态）。
 *
 * 以无类型回调形式暴露，避免 feature 模块反向依赖 `:app`（否则出现循环依赖）。
 */
val LocalOnLoginConfirmed: ProvidableCompositionLocal<(() -> Unit)?> = compositionLocalOf { null }

/**
 * 登出成功后的刷新回调（由 `:app` 提供，触发 `AppGraph.onLoggedOut()` 清空全局
 * 登录态）。
 *
 * 以无类型回调形式暴露，避免 feature 模块反向依赖 `:app`（否则出现循环依赖）。
 * 原 bug：`AppGraph.onLoggedOut()` 零调用，登出后全局登录态永不更新。
 */
val LocalOnLoggedOut: ProvidableCompositionLocal<(() -> Unit)?> = compositionLocalOf { null }

/**
 * 当前登录态（`true` 表示已登录），供各 feature 决定是否拉取登录专属内容（雷达歌单、
 * 我喜欢、最近播放等）。以 [StateFlow]<Boolean> 暴露，由 `:app` 由 `AppGraph.loginState`
 * 派生后下发——feature 模块因此无需反向依赖 `:app`。
 */
val LocalIsLoggedIn: ProvidableCompositionLocal<StateFlow<Boolean>> =
    compositionLocalOf { MutableStateFlow(false) }

/**
 * 进程级 Toast 状态（iOS `ToastCenter`）。由 `:app` 在根可组合处 `rememberToastState`
 * 后下发，任意 feature 屏幕可 `LocalToastState.current.show(...)` 触达同一中心。
 */
val LocalToastState: ProvidableCompositionLocal<ToastState> =
    compositionLocalOf { error("LocalToastState 必须在 KumoneApp 中提供") }

/**
 * 打开全屏播放页（官方客户端行为：点击歌曲即自动跳转播放页）。
 *
 * 由 `:app` 在根可组合处以 `overlayNav.navigate(NOW_PLAYING)` 提供；各 feature
 * 的点歌回调在起播后调用它，无需反向依赖 `:app` 或自带 NavController。
 */
val LocalOpenNowPlaying: ProvidableCompositionLocal<(() -> Unit)?> =
    compositionLocalOf { null }
