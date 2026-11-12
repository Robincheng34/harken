// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 设置页 ViewModel（T28 `SettingsViewModel.kt`）。
//
// 收集 `SettingsRepository.settings` 为 [SettingsUiState]，并转发各项写入；登出走
// `NeteaseApi.logout`。纯 Kotlin + 协程，可纯 JVM 单测。

package com.rc.harken.feature.settings

import com.rc.harken.net.api.NeteaseApi
import com.rc.harken.storage.update.UpdateRepository
import com.rc.harken.playback.AudioQuality
import com.rc.harken.storage.settings.AppAppearance
import com.rc.harken.storage.settings.LyricsAnnotation
import com.rc.harken.storage.settings.NavTabOption
import com.rc.harken.storage.settings.NowPlayingMode
import com.rc.harken.storage.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repository: SettingsRepository,
    private val api: NeteaseApi,
    private val updateRepository: UpdateRepository,
    private val isLoggedIn: () -> Boolean,
    private val nickname: () -> String,
    /** 登出成功后的回调（由宿主传入，用于刷新全局登录态 `AppGraph.onLoggedOut()`）。 */
    private val onLoggedOut: () -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val _uiState: MutableStateFlow<SettingsUiState> = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        scope.launch {
            repository.settings.collect { settings ->
                _uiState.update {
                    it.copy(
                        settings = settings,
                        isLoggedIn = isLoggedIn(),
                        nickname = nickname(),
                    )
                }
            }
        }
        // 进入页面时读取上次遗留的待更新信息，若已保存则直接反映在状态上
        // （例如用户上次点了「稍后」），无需重新请求即可提示安装。
        scope.launch {
            runCatching { updateRepository.getPendingUpdate() }.getOrNull()?.let { pending ->
                _uiState.update { it.copy(updateCheck = UpdateCheckUi.Available(pending)) }
            }
        }
        // 读取已保存的更新源地址（若有本地覆盖），回写状态供设置页展示。
        scope.launch {
            runCatching { updateRepository.getUpdateSourceUrl() }.getOrNull()?.let { url ->
                _uiState.update { it.copy(updateSourceUrl = url) }
            }
        }
    }

    fun setAudioQuality(quality: AudioQuality) = scope.launch {
        repository.setAudioQuality(quality)
    }

    fun setAppearance(appearance: AppAppearance) = scope.launch {
        repository.setAppearance(appearance)
    }

    fun setNowPlayingMode(mode: NowPlayingMode) = scope.launch {
        repository.setNowPlayingMode(mode)
    }

    fun setShowTranslation(show: Boolean) = scope.launch {
        repository.setShowTranslation(show)
    }

    fun setVerbatimLyrics(enabled: Boolean) = scope.launch {
        repository.setVerbatimLyrics(enabled)
    }

    fun setLyricsAnnotation(annotation: LyricsAnnotation) = scope.launch {
        repository.setLyricsAnnotation(annotation)
    }

    fun setUnblockEnabled(enabled: Boolean) = scope.launch {
        repository.setUnblockEnabled(enabled)
    }

    /** 迷你播放条下滑收起开关。 */
    fun setSwipeCollapseEnabled(enabled: Boolean) = scope.launch {
        repository.setSwipeCollapseEnabled(enabled)
    }

    /** 实验性：收起后显示把手重新唤起。 */
    fun setCollapseHandleEnabled(enabled: Boolean) = scope.launch {
        repository.setCollapseHandleEnabled(enabled)
    }

    /** 最近播放 / 近期删除确认开关。 */
    fun setRecentDeleteGuardEnabled(enabled: Boolean) = scope.launch {
        repository.setRecentDeleteGuardEnabled(enabled)
    }

    /** 通知栏播放控制卡片开关。 */
    fun setMediaControlsEnabled(enabled: Boolean) = scope.launch {
        repository.setMediaControlsEnabled(enabled)
    }

    /** 保存底部导航栏配置（可见 Tab 及顺序，见 [NavTabOption]）。 */
    fun setTabOrder(keys: List<String>) = scope.launch {
        repository.setTabOrder(keys)
    }

    /** 当前可见 Tab（基于 scope 内最新 settings 值派生；空 tabOrder = 全部默认）。 */
    private fun currentVisibleTabs(): List<NavTabOption> {
        val fromOrder = _uiState.value.settings.tabOrder.mapNotNull { NavTabOption.fromKey(it) }
            .ifEmpty { NavTabOption.entries.toList() }
        // 「我的」(LIBRARY) 为常驻 Tab，任何配置下强制可见（防止旧配置遗漏）。
        return if (NavTabOption.LIBRARY in fromOrder) fromOrder else fromOrder + NavTabOption.LIBRARY
    }

    /**
     * 切换某个 Tab 的可见性（[show] = true 显示 / false 隐藏）。
     *
     * 原 bug：UI 层用 remember(uiState) 派生的 visibleTabs 快照做读-改-写，
     * 快速连点时 DataStore 未回流、第二次操作覆盖第一次导致丢更新。现把意图
     * 计算收敛到 ViewModel：基于 scope 内最新 settings 值计算新 tabOrder 再写入。
     */
    fun toggleNavTab(option: NavTabOption, show: Boolean) = scope.launch {
        // 「我的」(LIBRARY) 为常驻 Tab，不可隐藏（设置页开关已禁用，此处再兜底）。
        if (option == NavTabOption.LIBRARY && !show) return@launch
        val visible = currentVisibleTabs()
        // 至少保留一个可见 Tab（隐藏最后一项直接忽略，兜底 UI 守卫）。
        if (!show && visible.size <= 1) return@launch
        val newOrder = if (show) {
            visible.map { it.key } + option.key
        } else {
            visible.filter { it != option }.map { it.key }
        }
        repository.setTabOrder(newOrder)
    }

    /** 上/下移某个可见 Tab（[delta]：-1 上移 / 1 下移），意图同样基于最新值计算。 */
    fun moveNavTab(option: NavTabOption, delta: Int) = scope.launch {
        val visible = currentVisibleTabs()
        val index = visible.indexOf(option)
        val target = index + delta
        if (index < 0 || target !in visible.indices) return@launch
        val reordered = visible.toMutableList().apply {
            removeAt(index)
            add(target, option)
        }
        repository.setTabOrder(reordered.map { it.key })
    }

    /** 退出登录（iOS 设置页「退出登录」）。 */
    fun logout() {
        scope.launch {
            _uiState.update { it.copy(isLoggingOut = true) }
            val result = runCatching { api.logout() }
            _uiState.update { it.copy(isLoggingOut = false, isLoggedIn = false, nickname = "") }
            // 原 bug：登出只清 cookie + 局部 UI 状态，全局登录态（AppGraph）永不
            // 更新（onLoggedOut() 零调用）。成功路径回调宿主刷新全局登录态。
            if (result.isSuccess) onLoggedOut()
        }
    }

    /** 取消内部协程（页面销毁时由 UI 调用，避免 DataStore 收集器常驻泄漏）。 */
    fun cancel() {
        scope.cancel()
    }

    // ── 在线检测更新 ──────────────────────────────────────────────

    /** 设置更新源地址（本地覆盖，回落默认常量）。 */
    fun setUpdateSourceUrl(url: String) = scope.launch {
        updateRepository.setUpdateSourceUrl(url)
        _uiState.update { it.copy(updateSourceUrl = url) }
    }

    /** 检查更新：拉取清单，比对已安装 versionCode，决定提示新版本或已是最新。 */
    fun checkUpdate() {
        scope.launch {
            _uiState.update { it.copy(updateCheck = UpdateCheckUi.Checking) }
            runCatching { updateRepository.fetchManifest() }
                .onSuccess { manifest ->
                    val installed = updateRepository.getInstalledVersion()
                    if (manifest.versionCode > installed.versionCode) {
                        updateRepository.savePendingUpdate(manifest)
                        _uiState.update { it.copy(updateCheck = UpdateCheckUi.Available(manifest)) }
                    } else {
                        updateRepository.clearPendingUpdate()
                        _uiState.update { it.copy(updateCheck = UpdateCheckUi.Latest(installed.versionName)) }
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(updateCheck = UpdateCheckUi.Error(e.message ?: "检查更新失败")) }
                }
        }
    }

    /** 收起更新提示（关弹窗 / 清除错误态），回到空闲。 */
    fun dismissUpdate() {
        _uiState.update { it.copy(updateCheck = UpdateCheckUi.Idle) }
    }

    /** 回写下载进度（fraction ∈ [0,1]），保持状态单一来源在 ViewModel。 */
    fun setDownloading(fraction: Float) {
        _uiState.update { it.copy(updateCheck = UpdateCheckUi.Downloading(fraction)) }
    }

    /** 回写下载 / 安装失败信息。 */
    fun setUpdateError(message: String) {
        _uiState.update { it.copy(updateCheck = UpdateCheckUi.Error(message)) }
    }
}
