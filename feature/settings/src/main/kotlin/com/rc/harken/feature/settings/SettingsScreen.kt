// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 设置页（T28 `SettingsScreen.kt`，iOS `SettingsView` 1:1 移植）。
//
// 二级菜单：所有设置分区收进可展开大类（播放 / 迷你播放条 / 外观 / 主题与挂件 /
// 歌词 / 高级 / 导航栏 / 账号 / 关于），同一时间只展开一组，点击标题展开、再点收起。

package com.rc.harken.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import com.rc.harken.design.theme.AppAnimations
import com.rc.harken.design.theme.GlassTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rc.harken.design.composition.LocalIsLoggedIn
import com.rc.harken.design.composition.LocalNeteaseApi
import com.rc.harken.design.composition.LocalOnLoggedOut
import com.rc.harken.design.composition.LocalSettingsRepository
import com.rc.harken.design.composition.LocalUpdateRepository
import com.rc.harken.design.theme.Layout
import com.rc.harken.storage.settings.NavTabOption
import com.rc.harken.storage.settings.CURATED_THEME_PACKS
import com.rc.harken.storage.settings.THEME_CATEGORY_SOLID
import com.rc.harken.storage.settings.THEME_CATEGORY_GRADIENT
import com.rc.harken.storage.settings.THEME_CATEGORY_DARK
import com.rc.harken.storage.settings.ThemeColorPack
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import java.io.File
import kotlinx.coroutines.launch

/** 设置页覆盖层路由（与 `:app` `Routes.SETTINGS` 的字符串契约）。 */
const val SETTINGS_ROUTE = "settings"

/**
 * 设置页。
 *
 * @param onBack 返回。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val repository = LocalSettingsRepository.current
        ?: error("LocalSettingsRepository 必须在 HarkenApp 提供")
    val api = LocalNeteaseApi.current ?: error("LocalNeteaseApi 必须在 HarkenApp 提供")
    val updateRepository = LocalUpdateRepository.current
        ?: error("LocalUpdateRepository 必须在 HarkenApp 提供")
    val loggedInFlow = LocalIsLoggedIn.current
    val isLoggedIn by loggedInFlow.collectAsStateWithLifecycle()

    val onLoggedOut = LocalOnLoggedOut.current
    val viewModel = remember(repository, api, updateRepository) {
        SettingsViewModel(
            repository = repository,
            api = api,
            updateRepository = updateRepository,
            isLoggedIn = { isLoggedIn },
            nickname = { "" },
            // 登出成功后刷新全局登录态（AppGraph.onLoggedOut，经 CompositionLocal 下发）。
            onLoggedOut = { onLoggedOut?.invoke() },
        )
    }
    // 页面销毁时取消 ViewModel 内部协程（原 bug：自建 scope 永不取消，DataStore
    // 收集器常驻泄漏）。
    DisposableEffect(Unit) { onDispose { viewModel.cancel() } }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = uiState.settings

    // 底部导航栏当前可见 Tab（按保存的顺序；未配置时为全部默认顺序）。
    // 「我的」(LIBRARY) 为常驻 Tab：任何配置下强制可见（仅可调整顺序、不可隐藏），
    // 兜底修复此前被隐藏的旧配置，避免行内出现「已隐藏 + 开关强制开启」的矛盾展示。
    val visibleTabs = remember(settings.tabOrder) {
        val fromOrder = settings.tabOrder.mapNotNull { NavTabOption.fromKey(it) }
            .ifEmpty { NavTabOption.entries.toList() }
        if (NavTabOption.LIBRARY in fromOrder) fromOrder else fromOrder + NavTabOption.LIBRARY
    }

    // 主题与挂件：展开态 + 当前套件名（未配置回落默认）。
    var themePackExpanded by remember { mutableStateOf(false) }
    var mainImageExpanded by remember { mutableStateOf(false) }
    var showCustomColorDialog by remember { mutableStateOf(false) }
    val currentThemePackName = remember(settings.activeThemePackId) {
        val id = settings.activeThemePackId
        when {
            id.isNullOrEmpty() -> "默认（品牌红）"
            else -> CURATED_THEME_PACKS.firstOrNull { it.id == id }?.label ?: "自定义"
        }
    }
    val currentMainImageName = remember(settings.activeMainImagePackId) {
        if (settings.activeMainImagePackId.isNullOrEmpty()) "默认渐变" else "已选自定义背景"
    }
    val settingsScope = rememberCoroutineScope()

    // ── 在线检测更新：下载文件、安装权限回调 ──────────────────────
    val ctx = LocalContext.current
    val currentVersion by remember {
        mutableStateOf(
            runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }
                .getOrDefault(""),
        )
    }
    val updateState = uiState.updateCheck
    var downloadedFile by remember { mutableStateOf<File?>(null) }

    // 更新源地址编辑弹窗状态。
    var showSourceUrlDialog by remember { mutableStateOf(false) }
    var sourceUrlDraft by remember { mutableStateOf("") }

    // 安装未知来源权限授予后，重新尝试安装已下载的 APK。
    val installPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (updateRepository.canInstallPackages()) {
            downloadedFile?.let { updateRepository.installApk(it) }
            viewModel.dismissUpdate()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            GlassTopAppBar(
                title = { Text(text = stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            // 二级菜单：所有设置分区收进可展开大类，同一时间只展开一组（默认展开「播放」）。
            var expandedGroup by remember { mutableStateOf<String?>("播放") }
            val onGroupToggle: (String) -> Unit = { key ->
                expandedGroup = if (expandedGroup == key) null else key
            }
            val sPlayback = stringResource(R.string.settings_playback)
            val sMini = stringResource(R.string.settings_miniplayer)
            val sAppearance = stringResource(R.string.settings_appearance)
            val sWidgets = stringResource(R.string.settings_widgets)
            val sLyrics = stringResource(R.string.settings_lyrics)
            val sAdvanced = stringResource(R.string.settings_advanced)
            val sNavbar = stringResource(R.string.settings_navbar)
            val sAccount = stringResource(R.string.settings_account)
            val sAbout = stringResource(R.string.settings_about)

            SettingsGroup(
                title = sPlayback,
                expanded = expandedGroup == sPlayback,
                onToggle = { onGroupToggle(sPlayback) },
            ) {
                ChoiceRow(
                    title = stringResource(R.string.settings_audio_quality),
                    options = AudioQualityOptions,
                    selected = settings.audioQuality,
                    label = { "${it.displayName} · ${it.params}" },
                    onSelect = viewModel::setAudioQuality,
                    trailing = { q -> QualityBadge(requiresLogin = q.requiresLogin) },
                )
                Text(
                    text = stringResource(R.string.settings_quality_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 24.dp, end = 24.dp, bottom = 4.dp),
                )
                ChoiceRow(
                    title = stringResource(R.string.settings_now_playing_mode),
                    options = NowPlayingModeOptions,
                    selected = settings.nowPlayingMode,
                    label = { it.displayName },
                    onSelect = viewModel::setNowPlayingMode,
                )
            }

            SettingsGroup(
                title = sMini,
                expanded = expandedGroup == sMini,
                onToggle = { onGroupToggle(sMini) },
            ) {
                SwitchRow(
                    title = stringResource(R.string.settings_swipe_collapse),
                    subtitle = stringResource(R.string.settings_swipe_collapse_summary),
                    checked = settings.swipeCollapseEnabled,
                    onCheckedChange = viewModel::setSwipeCollapseEnabled,
                )
                SwitchRow(
                    title = stringResource(R.string.settings_collapse_handle),
                    subtitle = stringResource(R.string.settings_collapse_handle_summary),
                    checked = settings.collapseHandleEnabled,
                    enabled = settings.swipeCollapseEnabled,
                    onCheckedChange = viewModel::setCollapseHandleEnabled,
                )
            }

            SettingsGroup(
                title = sAppearance,
                expanded = expandedGroup == sAppearance,
                onToggle = { onGroupToggle(sAppearance) },
            ) {
                ChoiceRow(
                    title = stringResource(R.string.settings_appearance),
                    options = AppearanceOptions,
                    selected = settings.appearance,
                    label = { it.displayName },
                    onSelect = viewModel::setAppearance,
                )
            }

            SettingsGroup(
                title = sWidgets,
                expanded = expandedGroup == sWidgets,
                onToggle = { onGroupToggle(sWidgets) },
            ) {
                SettingsExpandRow(
                    title = stringResource(R.string.settings_theme_pack),
                    subtitle = currentThemePackName,
                    expanded = themePackExpanded,
                    onToggle = { themePackExpanded = !themePackExpanded },
                )
                if (themePackExpanded) {
                    ThemeGallery(
                        selectedId = settings.activeThemePackId,
                        onSelect = { id -> settingsScope.launch { repository.setActiveThemePack(id) } },
                        onCustom = { showCustomColorDialog = true },
                    )
                }
                if (showCustomColorDialog) {
                    CustomColorDialog(
                        onDismiss = { showCustomColorDialog = false },
                        onConfirm = { hex ->
                            settingsScope.launch { repository.setActiveThemePack(hex) }
                            showCustomColorDialog = false
                        },
                    )
                }
                SettingsExpandRow(
                    title = stringResource(R.string.settings_main_image_pack),
                    subtitle = currentMainImageName,
                    expanded = mainImageExpanded,
                    onToggle = { mainImageExpanded = !mainImageExpanded },
                )
                if (mainImageExpanded) {
                    MainImagePicker(
                        selectedUri = settings.activeMainImagePackId,
                        onPick = { uri -> settingsScope.launch { repository.setActiveMainImagePack(uri) } },
                        onClear = { settingsScope.launch { repository.setActiveMainImagePack(null) } },
                    )
                }
            }

            SettingsGroup(
                title = sLyrics,
                expanded = expandedGroup == sLyrics,
                onToggle = { onGroupToggle(sLyrics) },
            ) {
                SwitchRow(
                    title = stringResource(R.string.settings_show_translation),
                    checked = settings.showTranslation,
                    onCheckedChange = viewModel::setShowTranslation,
                )
                SwitchRow(
                    title = stringResource(R.string.settings_verbatim),
                    checked = settings.verbatimLyrics,
                    onCheckedChange = viewModel::setVerbatimLyrics,
                )
                ChoiceRow(
                    title = stringResource(R.string.settings_lyrics_annotation),
                    options = LyricsAnnotationOptions,
                    selected = settings.lyricsAnnotation,
                    label = { it.displayName },
                    onSelect = viewModel::setLyricsAnnotation,
                )
            }

            SettingsGroup(
                title = sAdvanced,
                expanded = expandedGroup == sAdvanced,
                onToggle = { onGroupToggle(sAdvanced) },
            ) {
                SwitchRow(
                    title = stringResource(R.string.settings_unblock),
                    subtitle = stringResource(R.string.settings_unblock_summary),
                    checked = settings.unblockEnabled,
                    onCheckedChange = viewModel::setUnblockEnabled,
                )
                SwitchRow(
                    title = stringResource(R.string.settings_recent_delete_guard),
                    subtitle = stringResource(R.string.settings_recent_delete_guard_summary),
                    checked = settings.recentDeleteGuardEnabled,
                    onCheckedChange = viewModel::setRecentDeleteGuardEnabled,
                )
            }

            SettingsGroup(
                title = sNavbar,
                expanded = expandedGroup == sNavbar,
                onToggle = { onGroupToggle(sNavbar) },
            ) {
                Text(
                    text = stringResource(R.string.settings_navbar_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, bottom = 4.dp),
                )
                NavBarConfigSection(
                    visibleTabs = visibleTabs,
                    // 意图转发给 ViewModel（内部基于 scope 内最新 settings 值计算
                    // 新 tabOrder，快速连点不丢更新；原 bug：基于 remember 快照
                    // 读-改-写，第二次操作覆盖第一次）。
                    onToggle = viewModel::toggleNavTab,
                    onMove = viewModel::moveNavTab,
                )
                Text(
                    text = stringResource(R.string.settings_navbar_reset),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.setTabOrder(NavTabOption.entries.map { it.key }) }
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                )
            }

            if (uiState.isLoggedIn) {
                SettingsGroup(
                    title = sAccount,
                    expanded = expandedGroup == sAccount,
                    onToggle = { onGroupToggle(sAccount) },
                ) {
                    ClickableRow(
                        title = stringResource(R.string.settings_logout),
                        onClick = viewModel::logout,
                    )
                }
            }

            SettingsGroup(
                title = sAbout,
                expanded = expandedGroup == sAbout,
                onToggle = { onGroupToggle(sAbout) },
            ) {
                ClickableSubtitleRow(
                    title = "当前版本",
                    subtitle = currentVersion ?: "",
                    onClick = {},
                )
                ClickableSubtitleRow(
                    title = "更新源地址",
                    subtitle = if (uiState.updateSourceUrl.isBlank()) "默认（内置常量）" else uiState.updateSourceUrl,
                    onClick = {
                        sourceUrlDraft = uiState.updateSourceUrl
                        showSourceUrlDialog = true
                    },
                )
                ClickableSubtitleRow(
                    title = "检查更新",
                    subtitle = updateCheckSubtitle(uiState.updateCheck),
                    onClick = viewModel::checkUpdate,
                )
            }

            // 发现新版本 / 下载中弹窗（下载进度由 ViewModel.setDownloading 回写，
            // 安装需要 Activity Context 与未知来源权限，故放 Screen 层处理）。
            if (updateState is UpdateCheckUi.Available || updateState is UpdateCheckUi.Downloading) {
                val downloading = updateState is UpdateCheckUi.Downloading
                val manifest = (updateState as? UpdateCheckUi.Available)?.manifest
                AlertDialog(
                    onDismissRequest = { viewModel.dismissUpdate() },
                    confirmButton = {
                        if (downloading) {
                            TextButton(onClick = {}, enabled = false) {
                                Text("下载中 ${((updateState as UpdateCheckUi.Downloading).fraction * 100).toInt()}%")
                            }
                        } else {
                            TextButton(
                                enabled = updateState !is UpdateCheckUi.Downloading,
                                onClick = {
                                settingsScope.launch {
                                    viewModel.setDownloading(0f)
                                    runCatching {
                                        val file = updateRepository.downloadApk(manifest!!.apkUrl) { p ->
                                            viewModel.setDownloading(p.fraction)
                                        }
                                        downloadedFile = file
                                        if (updateRepository.canInstallPackages()) {
                                            updateRepository.installApk(file)
                                            viewModel.dismissUpdate()
                                        } else {
                                            installPermissionLauncher.launch(
                                                Intent(
                                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                                    Uri.parse("package:${ctx.packageName}"),
                                                ),
                                            )
                                        }
                                    }.onFailure { e ->
                                        viewModel.setUpdateError(e.message ?: "下载失败")
                                    }
                                }
                            }) { Text("立即更新") }
                        }
                    },
                    dismissButton = if (downloading) null else {
                        { TextButton(onClick = { viewModel.dismissUpdate() }) { Text("稍后") } }
                    },
                    title = {
                        Text(
                            if (downloading) {
                                "下载中…"
                            } else {
                                "发现新版本 ${manifest?.versionName ?: ""}"
                            },
                        )
                    },
                    text = {
                        if (downloading) {
                            Text("正在下载更新包 ${((updateState as UpdateCheckUi.Downloading).fraction * 100).toInt()}%")
                        } else {
                            Text(manifest?.updateNote?.ifBlank { "有新的版本可用，是否立即更新？" } ?: "有新的版本可用，是否立即更新？")
                        }
                    },
                )
            }

            // 更新源地址编辑弹窗（自托管 version.json：可手动填任意 HTTPS 地址，留空回落内置常量）。
            if (showSourceUrlDialog) {
                AlertDialog(
                    onDismissRequest = { showSourceUrlDialog = false },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.setUpdateSourceUrl(sourceUrlDraft.trim())
                            showSourceUrlDialog = false
                        }) { Text("保存") }
                    },
                    dismissButton = { TextButton(onClick = { showSourceUrlDialog = false }) { Text("取消") } },
                    title = { Text("更新源地址") },
                    text = {
                        Column {
                            Text(
                                text = "填写你的 version.json 地址（建议境内 OSS/COS + CDN）。留空则使用内置默认地址。检查更新时会拉取该地址并比对版本号。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(
                                value = sourceUrlDraft,
                                onValueChange = { sourceUrlDraft = it },
                                singleLine = true,
                                placeholder = { Text("https://your-cdn/harken/version.json") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    },
                )
            }

            Column(modifier = Modifier.padding(Layout.fullChromeClearance + Layout.bottomGestureInset())) {}
        }
    }
}

/**
 * 设置二级菜单分组：点击标题展开该类设置、再点收起；同一时间只展开一组（由调用方的
 * expanded 状态控制）。标题行复用 [SettingsExpandRow]，内容用 AnimatedVisibility 高度展开。
 */
@Composable
private fun SettingsGroup(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column {
        SettingsExpandRow(title = title, subtitle = "", expanded = expanded, onToggle = onToggle)
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = AppAnimations.enter()) + fadeIn(AppAnimations.enter()),
            exit = shrinkVertically(animationSpec = AppAnimations.exit()) + fadeOut(AppAnimations.exit()),
        ) {
            Column {
                content()
                Divider(modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/**
 * 导航栏自定义编辑区。
 *
 * 可见 Tab 按当前顺序排列（上下箭头调整顺序），隐藏 Tab 追加在列表尾部；
 * 开关控制显隐，至少保留一个可见 Tab（唯一可见项的开关不可关闭）。
 */
@Composable
private fun NavBarConfigSection(
    visibleTabs: List<NavTabOption>,
    onToggle: (NavTabOption, Boolean) -> Unit,
    onMove: (NavTabOption, Int) -> Unit,
) {
    val hiddenTabs = NavTabOption.entries.filter { it !in visibleTabs }
    val rows = visibleTabs.map { it to true } + hiddenTabs.map { it to false }
    Column {
        rows.forEach { (option, visible) ->
            val isLocked = option == NavTabOption.LIBRARY
            val visibleIndex = visibleTabs.indexOf(option)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = option.label, style = MaterialTheme.typography.bodyLarge)
                    when {
                        !visible -> Text(
                            text = "已隐藏",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        isLocked -> Text(
                            text = "不可隐藏，仅可调整顺序",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(
                    enabled = visible && visibleIndex > 0,
                    onClick = { onMove(option, -1) },
                ) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "上移")
                }
                IconButton(
                    enabled = visible && visibleIndex < visibleTabs.size - 1,
                    onClick = { onMove(option, 1) },
                ) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "下移")
                }
                Switch(
                    checked = visible || isLocked,
                    // 「我的」(LIBRARY) 为常驻 Tab：开关强制开启且禁用（仅可调整顺序、
                    // 不可隐藏）；其余 Tab 仍按原语义——只禁止「关闭最后一个可见项」。
                    enabled = !isLocked && (!visible || visibleTabs.size > 1),
                    onCheckedChange = { onToggle(option, it) },
                )
            }
        }
        if (visibleTabs.size <= 1) {
            Text(
                text = stringResource(R.string.settings_navbar_last_tab),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, top = 4.dp),
            )
        }
    }
}

/** 开关行。 */
@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(
                    alpha = if (enabled) 1f else 0.38f,
                ),
            )
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (enabled) 1f else 0.38f,
                    ),
                )
            }
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

/** 单选行（弹层式选择，简化为同行 RadioButton 列表）。 */
@Composable
private fun <T> ChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    trailing: @Composable ((T) -> Unit)? = null,
) {
    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        options.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(option) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                )
                Text(
                    text = label(option),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 8.dp),
                )
                if (trailing != null) {
                    Spacer(modifier = Modifier.weight(1f))
                    trailing(option)
                }
            }
        }
    }
}

/** 音质档位登录依赖标签：免登录（绿色调）/ 需登录会员（警示色调）。 */
@Composable
private fun QualityBadge(requiresLogin: Boolean) {
    val container: Color
    val content: Color
    val textRes: Int
    if (requiresLogin) {
        container = MaterialTheme.colorScheme.errorContainer
        content = MaterialTheme.colorScheme.onErrorContainer
        textRes = R.string.settings_quality_badge_login
    } else {
        container = MaterialTheme.colorScheme.primaryContainer
        content = MaterialTheme.colorScheme.onPrimaryContainer
        textRes = R.string.settings_quality_badge_free
    }
    Box(
        modifier = Modifier
            .background(container, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.labelSmall,
            color = content,
        )
    }
}

/** 可点击行。 */
@Composable
private fun ClickableRow(title: String, onClick: () -> Unit) {
    Text(
        text = title,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
    )
}

/** 设置页可展开行（主题挂件 / 主图挂件入口）。 */
@Composable
private fun SettingsExpandRow(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 带副标题的可点击行（当前版本 / 检查更新等）。 */
@Composable
private fun ClickableSubtitleRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 主题馆画廊（本地精选，分组按系展示：纯色系 / 渐变系 / 暗色系）。
 * 官方预设与自研精选同处一馆，逐卡标注「官方」/「本地精选」来源；末尾附免责声明。
 */
@Composable
private fun ThemeGallery(
    selectedId: String?,
    onSelect: (String) -> Unit,
    onCustom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups = listOf(
        THEME_CATEGORY_SOLID to stringResource(R.string.settings_theme_gallery_solid),
        THEME_CATEGORY_GRADIENT to stringResource(R.string.settings_theme_gallery_gradient),
        THEME_CATEGORY_DARK to stringResource(R.string.settings_theme_gallery_dark),
    )
    Column(modifier = modifier.fillMaxWidth()) {
        groups.forEach { (cat, title) ->
            val items = CURATED_THEME_PACKS.filter { it.category == cat }
            if (items.isNotEmpty()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(items, key = { it.id }) { pack ->
                        ThemeGalleryCard(pack, pack.id == selectedId, onSelect)
                    }
                    // 自定义取色入口（官方 ThemeColorDetailActivity 第 0 项：点击打开 HSV 取色器），置于纯色系末位。
                    if (cat == THEME_CATEGORY_SOLID) {
                        item(key = "custom") { CustomThemeCard(selectedId, onCustom) }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
        Text(
            text = stringResource(R.string.settings_theme_gallery_disclaimer),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
    }
}

/** 主题馆单卡：圆形预览（渐变系显示 color→color2 渐变），选中打勾+描边，下方标注名称与来源。 */
@Composable
private fun ThemeGalleryCard(
    pack: ThemeColorPack,
    selected: Boolean,
    onSelect: (String) -> Unit,
) {
    Column(
        modifier = Modifier.clickable { onSelect(pack.id) },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(
                    pack.color2?.let { c2 ->
                        Brush.linearGradient(listOf(Color(pack.color), Color(c2)))
                    } ?: SolidColor(Color(pack.color)),
                )
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = pack.label ?: pack.id,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            text = if (pack.official) {
                stringResource(R.string.settings_theme_official)
            } else {
                stringResource(R.string.settings_theme_local)
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (pack.official) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.tertiary
            },
        )
    }
}

/** 自定义取色入口卡（彩虹渐变 + 加号），选中态表示当前为自定义色。 */
@Composable
private fun CustomThemeCard(
    selectedId: String?,
    onCustom: () -> Unit,
) {
    val selected = selectedId != null && CURATED_THEME_PACKS.none { it.id == selectedId }
    Column(
        modifier = Modifier.clickable { onCustom() },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(
                    Brush.sweepGradient(
                        listOf(
                            Color.Red, Color.Yellow, Color.Green,
                            Color.Cyan, Color.Blue, Color.Magenta, Color.Red,
                        ),
                    ),
                )
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "自定义",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            text = "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 自定义取色（官方 ColorPicker HSV 模型复刻：H/S/V 三滑杆 + 实时预览）。 */
@Composable
private fun CustomColorDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var hue by remember { mutableStateOf(0f) }
    var sat by remember { mutableStateOf(0.8f) }
    var value by remember { mutableStateOf(0.9f) }
    // 用 Android 框架 HSVToColor 复刻官方 ColorPicker 的 HSV 取色模型。
    val argb = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm("%06X".format(0xFFFFFF and argb)) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("自定义主题色") },
        text = {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(argb)),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("色相", style = MaterialTheme.typography.labelMedium)
                Slider(value = hue, onValueChange = { hue = it }, valueRange = 0f..360f)
                Text("饱和度", style = MaterialTheme.typography.labelMedium)
                Slider(value = sat, onValueChange = { sat = it }, valueRange = 0f..1f)
                Text("明度", style = MaterialTheme.typography.labelMedium)
                Slider(value = value, onValueChange = { value = it }, valueRange = 0f..1f)
            }
        },
    )
}

/** 主图挂件选择（官方「主题背景」页行为：从相册选图 → 持久化 Uri → 「我的」页头部背景）。 */
@Composable
private fun MainImagePicker(
    selectedUri: String?,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            // 持久化读取权限，避免 App 重启后图片失效（官方选背景图后长期持有）。
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: Exception) { /* 部分来源不支持持久化，忽略 */ }
            onPick(it.toString())
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 64.dp, height = 48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(8.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (!selectedUri.isNullOrBlank()) {
                AsyncImage(
                    model = selectedUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_main_image_pack_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { launcher.launch("image/*") }) { Text("选择") }
        if (!selectedUri.isNullOrBlank()) {
            TextButton(onClick = onClear) { Text("清除") }
        }
    }
}

/** 「检查更新」行副标题文案，随 [UpdateCheckUi] 状态变化。 */
private fun updateCheckSubtitle(state: UpdateCheckUi): String = when (state) {
    UpdateCheckUi.Idle -> "点击检查新版本"
    UpdateCheckUi.Checking -> "正在检查…"
    is UpdateCheckUi.Latest -> "已是最新（${state.versionName}）"
    is UpdateCheckUi.Available -> "发现新版本 ${state.manifest.versionName}"
    is UpdateCheckUi.Error -> "检查失败"
    is UpdateCheckUi.Downloading -> "下载中 ${(state.fraction * 100).toInt()}%"
}
