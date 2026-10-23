// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 搜索落地页快捷入口的二级页：歌手榜 / 曲风（歌单分类）。
//
// 数据全部来自 `NeteaseApi` 既有方法，未新增任何端点：
//  - 歌手榜：`topArtists(limit)` → `/toplist/artist`；
//  - 曲风：`topPlaylists(category)` → `/playlist/list`，分类词取自 `:feature:explore`
//    的 `EXPLORE_CATEGORIES`（见 [GENRE_CATEGORIES]）。
//
// 结构同 `:feature:library` 的 `LibraryPages`：玻璃顶栏 + 内容，**不用** Scaffold 的
// `bottomBar` 预留底部空白（会留下常驻黑边），底部净空作为列表 footer 随内容滚动。

package com.rc.harken.feature.search.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.rc.harken.design.composition.LocalMusicController
import com.rc.harken.design.composition.LocalNeteaseApi
import com.rc.harken.design.theme.GlassTopAppBar
import com.rc.harken.design.theme.Layout
import com.rc.harken.feature.search.R
import com.rc.harken.net.model.ArtistSummary
import com.rc.harken.net.model.Formatters
import com.rc.harken.net.model.PlaylistSummary
import com.rc.harken.player.PlayerState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 曲风页分类（**抄自 `:feature:explore` 的 `EXPLORE_CATEGORIES`，保持一致**）。
 *
 * 剔除「排行榜 / 推荐歌单 / 精品歌单」三项：它们在发现页分别走 `toplists()` /
 * `personalizedPlaylists()` / `highQualityPlaylists()`，而本页统一走
 * `topPlaylists(cat = ...)`（`/playlist/list`），故不列入。除此之外未新造任何分类词。
 */
val GENRE_CATEGORIES: List<String> = listOf(
    "全部", "官方",
    "华语", "流行", "摇滚", "民谣", "电子", "轻音乐", "说唱", "爵士", "古典",
    "影视原声", "ACG", "古风", "怀旧", "治愈", "放松", "伤感", "快乐",
    "学习", "工作", "运动", "驾车", "夜晚",
)

/** 歌手榜拉取条数（`/toplist/artist` 的 limit）。 */
private const val TOP_ARTIST_LIMIT = 50

/** 曲风页每个分类拉取条数（`/playlist/list` 的 limit）。 */
private const val GENRE_PLAYLIST_LIMIT = 50

/** 歌手头像 / 歌单封面缩略尺寸（px，走 CDN `param=` 约定）。 */
private const val THUMB_SIZE = 200

// MARK: - 歌手榜

/**
 * 热门歌手榜（`topArtists`，端点 `/toplist/artist`）。
 *
 * @param onBack 顶栏返回。
 * @param onOpenArtist 点击歌手 → 跳转 `artist/{id}`（[com.rc.harken.design.navigation.Destinations.ARTIST_DETAIL]）。
 */
@Composable
fun ArtistsScreen(
    onBack: () -> Unit,
    onOpenArtist: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val api = LocalNeteaseApi.current ?: error("LocalNeteaseApi 必须在 HarkenApp 提供")
    val bottomClearance = rememberBottomClearance()

    var artists by remember { mutableStateOf<List<ArtistSummary>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    // 重试计数器：自增即触发 LaunchedEffect 重跑（错误态「重试」按钮）。
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(api, reloadKey) {
        isLoading = true
        error = null
        val result = runCatching { api.topArtists(limit = TOP_ARTIST_LIMIT) }.getOrNull()
        if (result != null) {
            artists = result
        } else {
            error = NETWORK_ERROR
        }
        isLoading = false
    }

    SubPageScaffold(
        title = stringResource(R.string.search_artists_title),
        onBack = onBack,
        modifier = modifier,
    ) {
        when {
            isLoading -> LoadingBox()
            error != null -> RetryBox(
                message = error ?: NETWORK_ERROR,
                onRetry = { reloadKey += 1 },
            )
            artists.isEmpty() -> EmptyBox(text = stringResource(R.string.search_artists_empty))
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(ARTIST_GRID_COLUMNS),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    horizontal = Layout.contentInset,
                    vertical = 12.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(items = artists, key = { it.id }) { artist ->
                    ArtistCell(
                        artist = artist,
                        onClick = { onOpenArtist(artist.id) },
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Spacer(modifier = Modifier.height(bottomClearance))
                }
            }
        }
    }
}

/** 歌手单元：圆形头像 + 名字 + 专辑数。 */
@Composable
private fun ArtistCell(
    artist: ArtistSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val cover = Formatters.resizedImageURL(artist.picUrl, THUMB_SIZE)
            if (!cover.isNullOrBlank()) {
                AsyncImage(
                    model = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = artist.name.take(1),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = artist.name.ifEmpty { UNKNOWN_NAME },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (artist.albumSize > 0) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${artist.albumSize} 张专辑",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// MARK: - 曲风（歌单分类）

/**
 * 曲风页（按歌单分类浏览，`topPlaylists`，端点 `/playlist/list`）。
 *
 * @param onBack 顶栏返回。
 * @param onOpenPlaylist 点击歌单 → 跳转 `playlist/{id}`。
 */
@Composable
fun GenreScreen(
    onBack: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val api = LocalNeteaseApi.current ?: error("LocalNeteaseApi 必须在 HarkenApp 提供")
    val bottomClearance = rememberBottomClearance()

    var category by remember { mutableStateOf(GENRE_CATEGORIES.first()) }
    var playlists by remember { mutableStateOf<List<PlaylistSummary>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    // 重试计数器：自增即触发 LaunchedEffect 重跑（错误态「重试」按钮）。
    var reloadKey by remember { mutableStateOf(0) }

    // 切换分类重新拉取（分类入 key，切换即取消上一次加载）。
    LaunchedEffect(api, category, reloadKey) {
        isLoading = true
        error = null
        val result = runCatching {
            api.topPlaylists(category = category, limit = GENRE_PLAYLIST_LIMIT)
        }.getOrNull()
        if (result != null) {
            playlists = result.playlists
        } else {
            error = NETWORK_ERROR
        }
        isLoading = false
    }

    SubPageScaffold(
        title = stringResource(R.string.search_genre_title),
        onBack = onBack,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            GenreChips(
                categories = GENRE_CATEGORIES,
                selected = category,
                onSelect = { category = it },
            )
            Spacer(modifier = Modifier.height(8.dp))

            when {
                isLoading -> LoadingBox()
                error != null -> RetryBox(
                    message = error ?: NETWORK_ERROR,
                    onRetry = { reloadKey += 1 },
                )
                playlists.isEmpty() -> EmptyBox(text = stringResource(R.string.search_genre_empty))
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(PLAYLIST_GRID_COLUMNS),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        horizontal = Layout.contentInset,
                        vertical = 4.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(items = playlists, key = { it.id }) { playlist ->
                        PlaylistCell(
                            playlist = playlist,
                            onClick = { onOpenPlaylist(playlist.id) },
                        )
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Spacer(modifier = Modifier.height(bottomClearance))
                    }
                }
            }
        }
    }
}

/**
 * 分类 chips 横滑行（样式对齐 `:feature:explore` 的 `CategoryChips`：
 * 选中 = primary 底白字，未选 = primary 6% 底 primary 字）。
 *
 * 直接显示分类原始文案（本页分类词本身就是中文），不走额外的文案资源映射。
 */
@Composable
private fun GenreChips(
    categories: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.width(Layout.contentInset - 8.dp))
        categories.forEach { category ->
            val isSelected = category == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(
                        if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
                        },
                    )
                    .clickable { onSelect(category) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = category,
                    color = if (isSelected) {
                        Color.White
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
        Spacer(modifier = Modifier.width(Layout.contentInset - 8.dp))
    }
}

/** 歌单单元：方形封面（右下角播放量角标）+ 两行标题。 */
@Composable
private fun PlaylistCell(
    playlist: PlaylistSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            val cover = Formatters.resizedImageURL(playlist.coverURL, THUMB_SIZE)
            if (!cover.isNullOrBlank()) {
                AsyncImage(
                    model = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (playlist.playCount > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = Formatters.playCount(playlist.playCount),
                        color = Color.White,
                        fontSize = 10.sp,
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = playlist.name.ifEmpty { UNKNOWN_NAME },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// MARK: - 通用骨架

/**
 * 二级页骨架：玻璃顶栏 + 内容。
 *
 * 不使用 Scaffold 的 `bottomBar`（会在底部固定预留一条空白带，深色主题下即常驻黑边），
 * 底部净空由各页塞进列表 footer（见 [rememberBottomClearance]）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubPageScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            GlassTopAppBar(
                title = { Text(text = title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.search_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) { content() }
    }
}

/**
 * 底部净空：无当前曲目时只需让开 TabBar，有曲目时还要让开迷你播放条
 * （与 `CloudScreen` 同款算法）。
 */
@Composable
private fun rememberBottomClearance(): Dp {
    val controller = LocalMusicController.current
    val playerState by (controller?.state ?: remember { MutableStateFlow(PlayerState()) })
        .collectAsStateWithLifecycle()
    return if (playerState.currentTrack != null) {
        Layout.fullChromeClearance + Layout.bottomGestureInset()
    } else {
        Layout.tabBarClearance + Layout.bottomGestureInset()
    }
}

/** 加载中（整屏居中转圈）。 */
@Composable
private fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

/** 空态（整屏居中灰字）。 */
@Composable
private fun EmptyBox(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 错误态（整屏居中：文案 + 重试按钮）。
 *
 * 重试通过自增页面内的 `reloadKey` 触发 `LaunchedEffect` 重跑，而非就地再起协程，
 * 避免与 LaunchedEffect 的加载并发写同一份状态。
 */
@Composable
private fun RetryBox(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onRetry) {
                Text(text = stringResource(R.string.search_retry))
            }
        }
    }
}

/** 歌手网格列数。 */
private const val ARTIST_GRID_COLUMNS = 3

/** 歌单网格列数。 */
private const val PLAYLIST_GRID_COLUMNS = 2

/** 网络/接口失败文案（与 `:feature:explore` 的 `ERROR_NETWORK` 同文案）。 */
private const val NETWORK_ERROR = "网络连接失败"

/** 名称缺失兜底。 */
private const val UNKNOWN_NAME = "未知"
