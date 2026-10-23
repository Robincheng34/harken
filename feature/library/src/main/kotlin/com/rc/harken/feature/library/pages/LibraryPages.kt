// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// P1 页面：每日推荐 / 最近播放 / 我的收藏 / 音乐云盘（T30）。
//
// 对应 iOS `Sources/Harken/Features/Pages/LibraryPages.swift`（iOS 侧同样是
// **单文件多页**：`RecentsView` + `CloudView` 等并列定义在一个文件里）。因此本文件把
// 四页聚合在一起，而非拆成四个文件——按「iOS 源码优先」原则，这一组织比逐页拆文件
// 更忠于原稿，故未照搬架构文档 §3 文件清单里 `DailySongsScreen.kt` /
// `RecentPlaysScreen.kt` 的单页文件粒度。
//
// 统一结构：顶部操作条 + 曲目列表 + 底部浮层净空。

package com.rc.harken.feature.library.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.rc.harken.design.theme.GlassTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.rc.harken.design.component.TrackListView
import com.rc.harken.design.composition.LocalDownloadRepository
import com.rc.harken.design.composition.LocalIsLoggedIn
import com.rc.harken.design.composition.LocalMusicController
import com.rc.harken.design.composition.LocalNeteaseApi
import com.rc.harken.design.composition.LocalPlaybackCommands
import com.rc.harken.design.composition.LocalSettingsRepository
import com.rc.harken.storage.settings.AppSettings
import com.rc.harken.design.theme.BrandRed
import com.rc.harken.design.theme.Layout
import com.rc.harken.net.model.AlbumRef
import com.rc.harken.net.model.AlbumSummary
import com.rc.harken.net.model.ArtistRef
import com.rc.harken.net.model.PlayRecordItem
import com.rc.harken.feature.library.PlayRecordHideStore
import com.rc.harken.feature.library.R
import com.rc.harken.net.model.Track
import com.rc.harken.playback.PlayContext
import com.rc.harken.playback.PlaySource
import com.rc.harken.player.PlayerState
import com.rc.harken.storage.db.DownloadedSongEntity
import com.rc.harken.storage.download.DownloadRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/**
 * 官方分段控件（「所有时间 / 最近一周」）：胶囊底 + 选中段高亮胶囊。
 *
 * 原实现是两颗并列 [Button]，视觉上像两个独立按钮、与官方分段控件观感不符，
 * 故改为单一胶囊容器 + 内部两段，选中段填品牌红。
 */
@Composable
private fun WeekSegmented(week: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(false to "所有时间", true to "最近一周").forEach { (value, label) ->
            val selected = value == week
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) BrandRed else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onChange(value) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    color = if (selected) {
                        androidx.compose.ui.graphics.Color.White
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/** 「播放全部」胶囊按钮（官方带总数「共 N 首」；[count] 为 null 时只显示「播放全部」）。 */
@Composable
// 注意参数顺序：onClick 必须是最后一个参数，否则调用处的尾随 lambda
// （PlayAllButton(enabled = ..., count = ...) { ... }）无法绑定到 onClick。
private fun PlayAllButton(
    enabled: Boolean,
    count: Int? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(containerColor = BrandRed),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        modifier = modifier,
    ) {
        Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.size(4.dp))
        Text(
            text = if (count != null) "播放全部 (共 $count 首)" else "播放全部",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 二级页统一骨架：顶栏 + 内容。
 *
 * 原实现带 `bottomBar = { Spacer(fullChromeClearance) }`：Scaffold 会在底部**固定**
 * 预留一条 142dp 的空白带（深色主题下就是一条显眼的黑边），列表再怎么滚它都在。
 * 现去掉 bottomBar，底部净空改由各页塞进**可滚动内容**的 footer（见各页
 * [TrackListView] 的 `footer` / Column 末尾 Spacer），滚到底才出现，不再有常驻黑边。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubPageScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = { Text(text = title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) { content() }
    }
}

// MARK: - 每日推荐

/** 每日推荐（T30，iOS `DailySongsView`）。 */
@Composable
fun DailySongsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val api = LocalNeteaseApi.current ?: error("LocalNeteaseApi 必须在 HarkenApp 提供")
    val commands = LocalPlaybackCommands.current
    val controller = LocalMusicController.current
    val scope = rememberCoroutineScope()

    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    val playerState by (controller?.state ?: remember { MutableStateFlow(PlayerState()) })
        .collectAsStateWithLifecycle()

    // 底部净空：作为列表 footer 随内容滚动，替掉原 Scaffold 的常驻黑边。
    val bottomClearance =
        if (playerState.currentTrack != null) {
            Layout.fullChromeClearance + Layout.bottomGestureInset()
        } else {
            Layout.tabBarClearance + Layout.bottomGestureInset()
        }

    LaunchedEffect(Unit) {
        isLoading = true
        tracks = runCatching { api.dailyRecommendSongs() }.getOrNull().orEmpty()
        isLoading = false
    }

    SubPageScaffold(
        title = stringResource(R.string.library_daily_recommend),
        onBack = onBack,
    ) {
        Column(modifier = modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayAllButton(enabled = tracks.isNotEmpty(), count = tracks.size) {
                    scope.launch { commands?.play(tracks, PlaySource.DAILY, PlayContext.daily) }
                }
            }
            // 同上方「最近播放」：列表取剩余高度而非整屏，避免末尾曲目被顶出屏幕。
            if (isLoading) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                TrackListView(
                    tracks = tracks,
                    currentTrackId = playerState.currentTrack?.id,
                    onPlay = { _, index ->
                        scope.launch {
                            commands?.play(tracks, PlaySource.DAILY, PlayContext.daily, index)
                        }
                    },
                    footer = { item { Spacer(modifier = Modifier.height(bottomClearance)) } },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// MARK: - 最近播放

/**
 * 播放记录的稳定隐藏键。
 *
 * `/v1/play/record` 返回的 `song.id` 可能为 0 或不唯一（实测删一条会因 id 相同把
 * 整列记录全过滤掉），故用 曲名 + 歌手 + 时长 组合成每条记录的稳定键：既能唯一定位
 * 单条，也能在切换「所有时间 / 最近一周」重新拉取后依旧命中、不再回弹。
 */
internal fun playRecordKey(song: Track): String =
    "${song.name}|${song.artistNames}|${song.durationMS}"

/** 最近播放（T30，iOS `RecentsView`，支持「所有时间 / 最近一周」分段）。 */
@Composable
fun RecentPlaysScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val api = LocalNeteaseApi.current ?: error("LocalNeteaseApi 必须在 HarkenApp 提供")
    val commands = LocalPlaybackCommands.current
    val controller = LocalMusicController.current
    val scope = rememberCoroutineScope()

    var week by remember { mutableStateOf(false) }
    var records by remember { mutableStateOf<List<PlayRecordItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    // 顶部垃圾桶的清空确认弹窗。
    var showClearConfirm by remember { mutableStateOf(false) }
    // 本页已隐藏的播放记录键：取自进程级 [PlayRecordHideStore]（不再是页面级 remember），
    // 这样退出「最近播放」页再进来（路由出栈再入栈）也不会丢态 → 记录不再回弹。
    // 注意：/v1/play/record 返回的 song.id 可能为 0 或不唯一，不能用 id 作键，
    // 否则删一条会把整列同 id 记录全过滤掉（曾出现「删一首→全表清空」）。改用组合键。
    val hiddenKeys by PlayRecordHideStore.hiddenKeys.collectAsStateWithLifecycle()

    val playerState by (controller?.state ?: remember { MutableStateFlow(PlayerState()) })
        .collectAsStateWithLifecycle()

    // 删除确认开关（设置「高级」项）：开启后「最近播放」左滑停顿出现红条，
    // 需继续滑动或点击红色区域才删除。同时作用于「我的」页近期。
    val settingsFlow = LocalSettingsRepository.current?.settings
    val appSettings by settingsFlow?.collectAsStateWithLifecycle(initialValue = AppSettings())
        ?: remember { mutableStateOf(AppSettings()) }
    val deleteGuard = appSettings.recentDeleteGuardEnabled

    // 底部净空：作为列表 footer 随内容滚动（滚到底才出现），
    // 不再用 Scaffold 固定 bottomBar —— 那会在底部常驻一条 142dp 黑边。
    val bottomClearance =
        if (playerState.currentTrack != null) {
            Layout.fullChromeClearance + Layout.bottomGestureInset()
        } else {
            Layout.tabBarClearance + Layout.bottomGestureInset()
        }

    LaunchedEffect(week) {
        isLoading = true
        val uid = runCatching { api.userAccount() }.getOrNull()?.userId
        records = if (uid != null) {
            runCatching { api.playRecords(uid, week) }.getOrNull().orEmpty()
        } else {
            emptyList()
        }
        isLoading = false
    }

    // 起播即取消隐藏：正在播放的曲若此前被本地删除/清空过，重新现身。
    // 键为静态组合（曲名|歌手|时长），删过再播放时键不变，若不摘掉就会被
    // hiddenKeys 继续挡着，用户看到的就像「最近播放不再记录新播放」。
    // 只跟 currentTrack?.id：同首歌重复起播不会重复触发，换歌才重新求值。
    LaunchedEffect(playerState.currentTrack?.id) {
        playerState.currentTrack?.let { PlayRecordHideStore.unhide(playRecordKey(it)) }
    }

    val visibleRecords = remember(records, hiddenKeys) {
        records.filter { playRecordKey(it.song) !in hiddenKeys }
    }
    val tracks = remember(visibleRecords) { visibleRecords.map { it.song } }

    SubPageScaffold(
        title = stringResource(R.string.library_recent_plays),
        onBack = onBack,
    ) {
        Column(modifier = modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WeekSegmented(week = week, onChange = { week = it })
                // 「播放全部」后紧跟垃圾桶：清空全部播放记录。两者成组靠右，
                // 保持播放全部仍在右侧、不被分段控件挤到中间。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // 官方：播放全部带总数（共 N 首）。weight(fill=false)：操作条超宽时
                    // 让播放全部收缩/省略号，把固定宽度让给垃圾桶，避免垃圾桶被横向压扁。
                    PlayAllButton(
                        enabled = tracks.isNotEmpty(),
                        count = tracks.size,
                        modifier = Modifier.weight(1f, fill = false),
                    ) {
                        scope.launch {
                            commands?.play(tracks, PlaySource.NONE, PlayContext.recents)
                        }
                    }
                    // 清空全部播放记录。requiredSize：无论父级给多窄都固定 44dp，从根上
                    // 杜绝「进场大图标→加载后变窄瘪掉」的横向压扁（内部 Icon 36dp 保持正方）。
                    val canClear = tracks.isNotEmpty()
                    Box(
                        modifier = Modifier
                            .requiredSize(44.dp)
                            .clickable(enabled = canClear, onClick = { showClearConfirm = true }),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "删除",
                            colorFilter = ColorFilter.tint(
                                MaterialTheme.colorScheme.onSurfaceVariant
                                    .copy(alpha = if (canClear) 1f else 0.38f),
                            ),
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
            }
            // 高度修复：本列是「操作条 + 列表」的固定结构，列表用 fillMaxSize 会取到
            // 整屏高度，使 Column 总高 = 操作条 + 整屏 > 可用高度，末尾曲目被顶到屏幕外
            // 且外层不可滚。改用 weight(1f) 取剩余高度，列表内部滚动即可到底。
            if (isLoading) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (tracks.isEmpty()) {
                // fillMaxWidth：父 Column 默认 Start 对齐，若 Box 仅包内容宽度会贴在左侧，
                // 需撑满宽度后 contentAlignment=Center 才能真正把空态文案居中。
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "暂无播放记录",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                TrackListView(
                    tracks = tracks,
                    currentTrackId = playerState.currentTrack?.id,
                    onPlay = { track, index ->
                        scope.launch {
                            commands?.playFrom(
                                tracks = tracks,
                                startTrackId = track.id,
                                source = PlaySource.NONE,
                                context = PlayContext.recents,
                            )
                        }
                    },
                    // 左滑单曲删除：按组合键隐藏该条让 UI 立即消失，再请求服务端。
                    // 接口为自编/可能不生效，hiddenKeys 保证同一次页面停留内不再回弹。
                    onDelete = { track ->
                        PlayRecordHideStore.hide(playRecordKey(track))
                        scope.launch { runCatching { api.deletePlayRecord(track.id) } }
                    },
                    // 底部净空随列表滚动，替掉原先 Scaffold 的常驻黑边。
                    footer = { item { Spacer(modifier = Modifier.height(bottomClearance)) } },
                    guard = deleteGuard,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (showClearConfirm) {
            AlertDialog(
                onDismissRequest = { showClearConfirm = false },
                title = { Text(text = "清空播放记录") },
                text = { Text(text = "确定要清空全部播放记录吗？该操作不可撤销。") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showClearConfirm = false
                            PlayRecordHideStore.hideAll(records.map { playRecordKey(it.song) })
                            scope.launch { runCatching { api.clearPlayRecords() } }
                        },
                    ) {
                        Text(text = "清空", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearConfirm = false }) { Text(text = "取消") }
                },
            )
        }
    }
}

// MARK: - 我的收藏

/** 我的收藏（T30，iOS `CollectionsView`）：收藏的专辑 + 歌手。 */
@Composable
fun FavoritesScreen(
    onBack: () -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onOpenArtist: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val api = LocalNeteaseApi.current ?: error("LocalNeteaseApi 必须在 HarkenApp 提供")

    var albums by remember { mutableStateOf<List<AlbumSummary>>(emptyList()) }
    var artists by remember {
        mutableStateOf<List<com.rc.harken.net.model.ArtistSummary>>(emptyList())
    }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        isLoading = true
        albums = runCatching { api.likedAlbums() }.getOrNull().orEmpty()
        artists = runCatching { api.likedArtists() }.getOrNull().orEmpty()
        isLoading = false
    }

    SubPageScaffold(
        title = stringResource(R.string.library_favorites),
        onBack = onBack,
    ) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
                return@Column
            }
            if (albums.isNotEmpty()) {
                com.rc.harken.design.component.SectionHeader(title = "收藏的专辑")
                com.rc.harken.design.component.PlaylistShelf(
                    title = "",
                    items = albums.map {
                        com.rc.harken.net.model.PlaylistSummary(
                            id = it.id,
                            name = it.name,
                            coverURL = it.picUrl,
                        )
                    },
                    onOpen = onOpenAlbum,
                    staggered = true,
                )
            }
            if (artists.isNotEmpty()) {
                com.rc.harken.design.component.SectionHeader(title = "关注的歌手")
                com.rc.harken.design.component.PlaylistShelf(
                    title = "",
                    items = artists.map {
                        com.rc.harken.net.model.PlaylistSummary(
                            id = it.id,
                            name = it.name,
                            coverURL = it.picUrl,
                        )
                    },
                    onOpen = onOpenArtist,
                    staggered = true,
                )
            }
            Spacer(modifier = Modifier.height(Layout.fullChromeClearance + Layout.bottomGestureInset()))
        }
    }
}

// MARK: - 音乐云盘

/** 音乐云盘只读列表（T30，iOS `CloudView`）。 */
@Composable
fun CloudScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val api = LocalNeteaseApi.current ?: error("LocalNeteaseApi 必须在 HarkenApp 提供")
    val commands = LocalPlaybackCommands.current
    val controller = LocalMusicController.current
    val scope = rememberCoroutineScope()

    var items by remember {
        mutableStateOf<List<com.rc.harken.net.model.CloudSongItem>>(emptyList())
    }
    var sizeInfo by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    val playerState by (controller?.state ?: remember { MutableStateFlow(PlayerState()) })
        .collectAsStateWithLifecycle()

    // 底部净空：作为列表 footer 随内容滚动，替掉原 Scaffold 的常驻黑边。
    val bottomClearance =
        if (playerState.currentTrack != null) {
            Layout.fullChromeClearance + Layout.bottomGestureInset()
        } else {
            Layout.tabBarClearance + Layout.bottomGestureInset()
        }

    LaunchedEffect(Unit) {
        isLoading = true
        val response = runCatching { api.cloudSongs() }.getOrNull()
        items = response?.data.orEmpty()
        val size = response?.size
        val max = response?.maxSize
        if (size != null && max != null && max > 0) {
            sizeInfo = String.format("已使用 %.1f GB / %.0f GB", size / 1_073_741_824.0, max / 1_073_741_824.0)
        }
        isLoading = false
    }

    val tracks = remember(items) { items.mapNotNull { it.simpleSong } }

    SubPageScaffold(
        title = stringResource(R.string.library_cloud),
        onBack = onBack,
    ) {
        Column(modifier = modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (sizeInfo != null) {
                    Text(
                        text = sizeInfo!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Spacer(modifier = Modifier.height(1.dp))
                }
                PlayAllButton(enabled = tracks.isNotEmpty(), count = tracks.size) {
                    scope.launch {
                        commands?.play(tracks, PlaySource.CLOUD, PlayContext.cloud)
                    }
                }
            }
            // 同上：列表/空态取剩余高度，避免末尾内容被顶出屏幕且无法滚动查看。
            if (isLoading) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (tracks.isEmpty()) {
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "云盘还没有歌曲",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "在网易云音乐客户端上传的歌曲会出现在这里",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                TrackListView(
                    tracks = tracks,
                    currentTrackId = playerState.currentTrack?.id,
                    onPlay = { track, index ->
                        scope.launch {
                            commands?.playFrom(
                                tracks = tracks,
                                startTrackId = track.id,
                                source = PlaySource.CLOUD,
                                context = PlayContext.cloud,
                            )
                        }
                    },
                    footer = { item { Spacer(modifier = Modifier.height(bottomClearance)) } },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// MARK: - 已下载

/**
 * 已下载（离线播放闭环的已下载管理页）。
 *
 * 列表来自本地 Room 清单（[DownloadRepository.observeDownloads]），无需任何网络请求；
 * 点击整行用实体构造 [Track] 并离线播放——[LocalSongSource] 会让 [SongUrlResolver]
 * 优先返回本地 `file://` URI，实现零改 ExoPlayer 的离线播放；右侧删除图标删除本地文件 +
 * Room 记录。
 */
@Composable
fun DownloadedScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val repo = LocalDownloadRepository.current
    val commands = LocalPlaybackCommands.current
    val controller = LocalMusicController.current
    val scope = rememberCoroutineScope()

    val downloads by (repo?.observeDownloads() ?: emptyFlow<List<DownloadedSongEntity>>())
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val playerState by (controller?.state ?: remember { MutableStateFlow(PlayerState()) })
        .collectAsStateWithLifecycle()

    // 底部净空：作为列表 footer 随内容滚动（滚到底才出现），不再用常驻黑边。
    val bottomClearance =
        if (playerState.currentTrack != null) {
            Layout.fullChromeClearance + Layout.bottomGestureInset()
        } else {
            Layout.tabBarClearance + Layout.bottomGestureInset()
        }

    SubPageScaffold(
        title = stringResource(R.string.library_downloaded),
        onBack = onBack,
    ) {
        Column(modifier = modifier.fillMaxSize()) {
            if (downloads.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "暂无下载内容",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(downloads, key = { it.id }) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    // 用实体构造 Track 并离线播放：SongUrlResolver 命中本地
                                    // file:// URI，无需任何网络。
                                    val track = Track(
                                        id = item.trackId,
                                        name = item.title,
                                        artists = listOf(ArtistRef(name = item.artist)),
                                        album = AlbumRef(name = item.album, picUrl = item.albumPicUrl),
                                        durationMS = item.durationMS,
                                    )
                                    scope.launch {
                                        commands?.playFrom(
                                            tracks = listOf(track),
                                            startTrackId = item.trackId,
                                            source = PlaySource.NONE,
                                            context = PlayContext.recents,
                                            openPlayer = true,
                                        )
                                    }
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AsyncImage(
                                model = item.albumPicUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${item.artist} · ${DownloadRepository.formatSize(item.sizeBytes)}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(
                                onClick = { scope.launch { repo?.delete(item.trackId, item.level) } },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = "删除",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(bottomClearance))
        }
    }
}
