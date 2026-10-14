// Ported from kumone by missuo (LGPL-3.0)
//
// 设置页（T28 `SettingsScreen.kt`，iOS `SettingsView` 1:1 移植）。
//
// 分组：播放（音质 / 播放页样式）/ 外观（主题）/ 歌词（翻译 / 逐字 / 注音）/
// 高级（灰色解锁，默认关）/ 账号（退出登录）。

package im.missuo.kumone.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import im.missuo.kumone.design.theme.GlassTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.missuo.kumone.design.composition.LocalIsLoggedIn
import im.missuo.kumone.design.composition.LocalNeteaseApi
import im.missuo.kumone.design.composition.LocalOnLoggedOut
import im.missuo.kumone.design.composition.LocalSettingsRepository
import im.missuo.kumone.design.theme.Layout
import im.missuo.kumone.storage.settings.NavTabOption

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
        ?: error("LocalSettingsRepository 必须在 KumoneApp 提供")
    val api = LocalNeteaseApi.current ?: error("LocalNeteaseApi 必须在 KumoneApp 提供")
    val loggedInFlow = LocalIsLoggedIn.current
    val isLoggedIn by loggedInFlow.collectAsStateWithLifecycle()

    val onLoggedOut = LocalOnLoggedOut.current
    val viewModel = remember(repository, api) {
        SettingsViewModel(
            repository = repository,
            api = api,
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
    val visibleTabs = remember(settings.tabOrder) {
        settings.tabOrder.mapNotNull { NavTabOption.fromKey(it) }
            .ifEmpty { NavTabOption.entries.toList() }
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
            // 播放
            SectionTitle(stringResource(R.string.settings_playback))
            ChoiceRow(
                title = stringResource(R.string.settings_audio_quality),
                options = AudioQualityOptions,
                selected = settings.audioQuality,
                label = { it.apiLevel },
                onSelect = viewModel::setAudioQuality,
            )
            ChoiceRow(
                title = stringResource(R.string.settings_now_playing_mode),
                options = NowPlayingModeOptions,
                selected = settings.nowPlayingMode,
                label = { it.displayName },
                onSelect = viewModel::setNowPlayingMode,
            )

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // 外观
            SectionTitle(stringResource(R.string.settings_appearance))
            ChoiceRow(
                title = stringResource(R.string.settings_appearance),
                options = AppearanceOptions,
                selected = settings.appearance,
                label = { it.displayName },
                onSelect = viewModel::setAppearance,
            )

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // 歌词
            SectionTitle(stringResource(R.string.settings_lyrics))
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

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // 高级（灰色解锁默认关）
            SectionTitle(stringResource(R.string.settings_advanced))
            SwitchRow(
                title = stringResource(R.string.settings_unblock),
                subtitle = stringResource(R.string.settings_unblock_summary),
                checked = settings.unblockEnabled,
                onCheckedChange = viewModel::setUnblockEnabled,
            )

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // 导航栏（自定义底部 Tab 显示与顺序）
            SectionTitle(stringResource(R.string.settings_navbar))
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

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // 账号
            SectionTitle(stringResource(R.string.settings_account))
            if (uiState.isLoggedIn) {
                ClickableRow(
                    title = stringResource(R.string.settings_logout),
                    onClick = viewModel::logout,
                )
            }

            Column(modifier = Modifier.padding(Layout.fullChromeClearance)) {}
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp),
    )
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
            val visibleIndex = visibleTabs.indexOf(option)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = option.label, style = MaterialTheme.typography.bodyLarge)
                    if (!visible) {
                        Text(
                            text = "已隐藏",
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
                    checked = visible,
                    // 原 bug：守卫写反（enabled = visible || visibleTabs.size > 1）——
                    // 唯一可见 Tab 时反而可关，newOrder 空串导致配置整体重置。
                    // 正确语义：只禁止「关闭最后一个可见项」，允许随时重新打开隐藏项。
                    enabled = !visible || visibleTabs.size > 1,
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
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
            }
        }
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
