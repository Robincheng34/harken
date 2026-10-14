// Ported from kumone by missuo (LGPL-3.0)
//
// P1 页面：每日推荐 / 最近播放 / 我的收藏 / 音乐云盘（T30）。
//
// 对应 iOS `Sources/Kumone/Features/Pages/LibraryPages.swift`（iOS 侧同样是
// **单文件多页**：`RecentsView` + `CloudView` 等并列定义在一个文件里）。因此本文件把
// 四页聚合在一起，而非拆成四个文件——按「iOS 源码优先」原则，这一组织比逐页拆文件
// 更忠于原稿，故未照搬架构文档 §3 文件清单里 `DailySongsScreen.kt` /
// `RecentPlaysScreen.kt` 的单页文件粒度。
//
// 统一结构：顶部操作条 + 曲目列表 + 底部浮层净空。

package im.missuo.kumone.feature.library.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import im.missuo.kumone.design.theme.GlassTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.missuo.kumone.design.component.TrackListView
import im.missuo.kumone.design.composition.LocalIsLoggedIn
import im.missuo.kumone.design.composition.LocalMusicController
import im.missuo.kumone.design.composition.LocalNeteaseApi
import im.missuo.kumone.design.composition.LocalPlaybackCommands
import im.missuo.kumone.design.theme.BrandRed
import im.missuo.kumone.design.theme.Layout
import im.missuo.kumone.net.model.AlbumSummary
import im.missuo.kumone.net.model.PlayRecordItem
import im.missuo.kumone.feature.library.R
import im.missuo.kumone.net.model.Track
import im.missuo.kumone.playback.PlayContext
import im.missuo.kumone.playback.PlaySource
import im.missuo.kumone.player.PlayerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** 分段控件（iOS `.pickerStyle(.segmented)`）。 */
@Composable
private fun WeekSegmented(week: Boolean, onChange: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(false to "所有时间", true to "最近一周").forEach { (value, label) ->
            Button(
                onClick = { onChange(value) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (value == week) BrandRed else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (value == week) {
                        androidx.compose.ui.graphics.Color.White
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                ),
            ) {
                Text(text = label)
            }
        }
    }
}

/** 「播放全部」胶囊按钮（iOS accentGradient Capsule）。 */
@Composable
private fun PlayAllButton(enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(containerColor = BrandRed),
    ) {
        Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.size(4.dp))
        Text(text = "播放全部")
    }
}

/** 二级页统一骨架：顶栏 + 内容 + 底部净空。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubPageScaffold(
    title: String,
    onBack: () -> Unit,
    bottomBar: @Composable () -> Unit,
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
        bottomBar = bottomBar,
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
    val api = LocalNeteaseApi.current ?: error("LocalNeteaseApi 必须在 KumoneApp 提供")
    val commands = LocalPlaybackCommands.current
    val controller = LocalMusicController.current
    val scope = rememberCoroutineScope()

    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    val playerState by (controller?.state ?: remember { MutableStateFlow(PlayerState()) })
        .collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        isLoading = true
        tracks = runCatching { api.dailyRecommendSongs() }.getOrNull().orEmpty()
        isLoading = false
    }

    SubPageScaffold(
        title = stringResource(R.string.library_daily_recommend),
        onBack = onBack,
        bottomBar = { Spacer(modifier = Modifier.height(Layout.fullChromeClearance)) },
    ) {
        Column(modifier = modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayAllButton(enabled = tracks.isNotEmpty()) {
                    scope.launch { commands?.play(tracks, PlaySource.DAILY, PlayContext.daily) }
                }
            }
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

// MARK: - 最近播放

/** 最近播放（T30，iOS `RecentsView`，支持「所有时间 / 最近一周」分段）。 */
@Composable
fun RecentPlaysScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val api = LocalNeteaseApi.current ?: error("LocalNeteaseApi 必须在 KumoneApp 提供")
    val commands = LocalPlaybackCommands.current
    val controller = LocalMusicController.current
    val scope = rememberCoroutineScope()

    var week by remember { mutableStateOf(false) }
    var records by remember { mutableStateOf<List<PlayRecordItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    val playerState by (controller?.state ?: remember { MutableStateFlow(PlayerState()) })
        .collectAsStateWithLifecycle()

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

    val tracks = remember(records) { records.map { it.song } }

    SubPageScaffold(
        title = stringResource(R.string.library_recent_plays),
        onBack = onBack,
        bottomBar = { Spacer(modifier = Modifier.height(Layout.fullChromeClearance)) },
    ) {
        Column(modifier = modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WeekSegmented(week = week, onChange = { week = it })
                PlayAllButton(enabled = tracks.isNotEmpty()) {
                    scope.launch {
                        commands?.play(tracks, PlaySource.NONE, PlayContext.recents)
                    }
                }
            }
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (tracks.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                    modifier = Modifier.fillMaxSize(),
                )
            }
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
    val api = LocalNeteaseApi.current ?: error("LocalNeteaseApi 必须在 KumoneApp 提供")

    var albums by remember { mutableStateOf<List<AlbumSummary>>(emptyList()) }
    var artists by remember {
        mutableStateOf<List<im.missuo.kumone.net.model.ArtistSummary>>(emptyList())
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
        bottomBar = { Spacer(modifier = Modifier.height(Layout.fullChromeClearance)) },
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
                im.missuo.kumone.design.component.SectionHeader(title = "收藏的专辑")
                im.missuo.kumone.design.component.PlaylistShelf(
                    title = "",
                    items = albums.map {
                        im.missuo.kumone.net.model.PlaylistSummary(
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
                im.missuo.kumone.design.component.SectionHeader(title = "关注的歌手")
                im.missuo.kumone.design.component.PlaylistShelf(
                    title = "",
                    items = artists.map {
                        im.missuo.kumone.net.model.PlaylistSummary(
                            id = it.id,
                            name = it.name,
                            coverURL = it.picUrl,
                        )
                    },
                    onOpen = onOpenArtist,
                    staggered = true,
                )
            }
            Spacer(modifier = Modifier.height(Layout.fullChromeClearance))
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
    val api = LocalNeteaseApi.current ?: error("LocalNeteaseApi 必须在 KumoneApp 提供")
    val commands = LocalPlaybackCommands.current
    val controller = LocalMusicController.current
    val scope = rememberCoroutineScope()

    var items by remember {
        mutableStateOf<List<im.missuo.kumone.net.model.CloudSongItem>>(emptyList())
    }
    var sizeInfo by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    val playerState by (controller?.state ?: remember { MutableStateFlow(PlayerState()) })
        .collectAsStateWithLifecycle()

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
        bottomBar = { Spacer(modifier = Modifier.height(Layout.fullChromeClearance)) },
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
                PlayAllButton(enabled = tracks.isNotEmpty()) {
                    scope.launch {
                        commands?.play(tracks, PlaySource.CLOUD, PlayContext.cloud)
                    }
                }
            }
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (tracks.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
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
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
