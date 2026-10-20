// Ported from kumone by missuo (LGPL-3.0)
//
// 搜索页屏幕（官方网易云搜索页 1:1 复刻）。
//
// 结构（完全照搬官方 Android 客户端）：
//  1. 顶部 pill 搜索栏（圆角浅灰底 + 放大镜 + 清除钮），聚焦/有词时右侧出现
//     红色「取消」；回车（imeAction=Search）或点取消均走官方语义；
//  2. 查询为空 → 落地页：热搜榜（排名彩数字 + 官方角标 + 热度值）+ 搜索历史
//     （流式胶囊 + 垃圾桶清空），见 [SearchLandingPage]；
//  3. 输入中（未提交）→ 联想下拉：关键词补全行（allMatch）+ 单曲/歌手/专辑/
//     歌单分区（带封面），点单曲即播、其余以该词提交搜索；
//  4. 已提交（showResults）→ [SearchSegmented] 五段切换 + [SearchResultSections]。
//
// 返回键行为同官方：先清空搜索回到落地页，再按才交还系统。

package im.missuo.kumone.feature.search

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import im.missuo.kumone.design.composition.LocalMusicController
import im.missuo.kumone.design.composition.LocalNeteaseApi
import im.missuo.kumone.design.composition.LocalPlaybackCommands
import im.missuo.kumone.design.composition.LocalSearchHistoryRepository
import im.missuo.kumone.design.navigation.Destinations
import im.missuo.kumone.design.theme.Layout
import im.missuo.kumone.feature.search.components.SearchLandingPage
import im.missuo.kumone.feature.search.components.SearchQuickEntry
import im.missuo.kumone.feature.search.components.SearchResultSections
import im.missuo.kumone.feature.search.components.SearchSegmented
import im.missuo.kumone.net.model.SearchSuggestBody
import im.missuo.kumone.net.model.Track
import im.missuo.kumone.playback.PlaySource
import im.missuo.kumone.player.PlayerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 搜索页主屏幕（官方样式）。
 *
 * @param navController 覆盖层导航控制器，用于跳转歌手/专辑/歌单详情。
 * @param bottomClearance 列表尾部净空（迷你播放条 + TabBar 悬浮 chrome 占位）。
 */
@Composable
fun SearchScreen(
    navController: NavHostController,
    bottomClearance: Dp = Layout.fullChromeClearance,
    onRequestHome: () -> Unit,
) {
    val api = LocalNeteaseApi.current
        ?: error("LocalNeteaseApi 必须在 KumoneApp 提供")
    val historyRepo = LocalSearchHistoryRepository.current
    val commands = LocalPlaybackCommands.current
    val scope = rememberCoroutineScope()

    val viewModel = remember(api, historyRepo) { SearchViewModel(api, historyRepo) }
    // 页面销毁时取消 ViewModel 内部协程（原 bug：自建 scope 永不取消，收集器常驻泄漏）。
    DisposableEffect(Unit) { onDispose { viewModel.cancel() } }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 当前播放曲（单曲列表高亮）。
    val controller = LocalMusicController.current
    val playerState by (controller?.state ?: remember { MutableStateFlow(PlayerState()) })
        .collectAsStateWithLifecycle()
    val currentTrackId: Long? = playerState.currentTrack?.id

    // 焦点与键盘：进入搜索页**不**自动聚焦、不弹键盘，仅用户主动点击输入框时才调起。
    // 原实现在 LaunchedEffect 里 requestFocus()，一进页面键盘就弹出并遮挡落地页内容；
    // 现只保留落地数据加载，聚焦完全交给用户点击（BasicTextField 点击即聚焦）。
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var searchFocused by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        viewModel.loadHotSearches()
    }

    // 返回键语义（照搬官方）：有查询/已搜索时先清空回落地页；落地态再按返回
    // 则切回首页而非退出应用（在首页再按返回才交还系统回桌面）。
    BackHandler(enabled = true) {
        if (uiState.query.isNotEmpty() || uiState.hasSearched) {
            viewModel.setQuery("")
            searchFocused = false
            keyboardController?.hide()
        } else {
            onRequestHome()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        SearchTopBar(
            query = uiState.query,
            isSearching = uiState.isSearching,
            showCancel = searchFocused || uiState.query.isNotEmpty() || uiState.hasSearched,
            focusRequester = focusRequester,
            onFocusChanged = { searchFocused = it },
            onQueryChange = viewModel::setQuery,
            onSubmit = {
                viewModel.submit(uiState.query)
                keyboardController?.hide()
            },
            onClear = { viewModel.setQuery("") },
            onScan = { navController.navigate(Destinations.SCAN) },
            onCancel = {
                viewModel.setQuery("")
                searchFocused = false
                keyboardController?.hide()
            },
        )

        when {
            // 已提交搜索 → 分段控件 + 结果（官方：提交后才出结果页）。
            uiState.showResults -> {
                SearchSegmented(
                    selected = uiState.tab,
                    onSelect = viewModel::selectTab,
                )
                Spacer(modifier = Modifier.height(8.dp))
                SearchResultsBody(
                    uiState = uiState,
                    currentTrackId = currentTrackId,
                    bottomClearance = bottomClearance,
                    // 关键修复：结果区取「剩余高度」而非 fillMaxSize。外层是**不可滚动**的
                    // Column，fillMaxSize 会让本区高度等于整屏，Column 总高 =
                    // 搜索栏+分段条+整屏 > 屏幕，于是排在最末的「歌单」分区被顶到屏幕之外，
                    // 而外层又无法滚动 → 歌单永远看不到。改用 weight(1f) 后本区高度 =
                    // 剩余空间，LazyColumn / LazyVerticalGrid 自身滚动即可滚到末尾。
                    modifier = Modifier.weight(1f),
                    onPlayTrack = { track ->
                        scope.launch {
                            commands?.playFrom(
                                uiState.songs,
                                track.id,
                                PlaySource.PLAYLIST,
                                null,
                            )
                        }
                    },
                    onOpenArtist = { navController.navigate(Destinations.artistDetail(it)) },
                    onOpenAlbum = { navController.navigate(Destinations.albumDetail(it)) },
                    onOpenPlaylist = { navController.navigate(Destinations.playlistDetail(it)) },
                    onSelectTab = viewModel::selectTab,
                )
            }

            // 查询为空 → 官方落地页（热搜榜 + 搜索历史）。
            uiState.query.isBlank() -> SearchLandingPage(
                uiState = uiState,
                onHotItemClick = viewModel::submit,
                onHistoryItemClick = viewModel::submit,
                onClearHistory = viewModel::clearHistory,
                onQuickEntryClick = { entry ->
                    when (entry) {
                        // 已落地：跳覆盖层二级页（均在 `:feature:search` 的 searchGraph 注册）。
                        SearchQuickEntry.ARTIST -> navController.navigate(Destinations.ARTISTS)
                        SearchQuickEntry.GENRE -> navController.navigate(Destinations.GENRE)
                    }
                },
                bottomClearance = bottomClearance,
                modifier = Modifier.weight(1f),
            )

            // 输入中（未提交）→ 官方联想下拉。
            else -> SuggestionBlock(
                query = uiState.query,
                suggestions = uiState.suggestions,
                onPlaySong = { track ->
                    scope.launch {
                        commands?.playFrom(
                            uiState.suggestions?.songs.orEmpty(),
                            track.id,
                            PlaySource.PLAYLIST,
                            null,
                        )
                    }
                },
                onSubmitKeyword = viewModel::submit,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 已提交搜索后的结果区（加载态 / 空态 / 结果分区）。
 *
 * 从原 `SearchScreen` 结果分支原样搬出：有联想但当前 Tab 尚未产出结果时先给
 * 联想（iOS `.searchable` 下拉语义），否则走 [SearchResultSections]。
 */
@Composable
private fun SearchResultsBody(
    uiState: SearchUiState,
    currentTrackId: Long?,
    bottomClearance: Dp,
    onPlayTrack: (Track) -> Unit,
    onOpenArtist: (Long) -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onSelectTab: (SearchTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        uiState.isSearching && uiState.currentEmpty() ->
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }

        uiState.currentEmpty() ->
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = uiState.error ?: stringResource(R.string.search_no_result),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

        else -> SearchResultSections(
            uiState = uiState,
            currentTrackId = currentTrackId,
            onPlayTrack = { track, _ -> onPlayTrack(track) },
            onOpenArtist = onOpenArtist,
            onOpenAlbum = onOpenAlbum,
            onOpenPlaylist = onOpenPlaylist,
            onSelectTab = onSelectTab,
            modifier = modifier.fillMaxSize(),
            bottomClearance = bottomClearance,
        )
    }
}

/**
 * 官方顶部搜索栏：圆角 pill 浅灰底 + 放大镜 + 清除钮，右侧红色「取消」。
 *
 * 用 [BasicTextField] 手搓而非 Material3 `TextField`：官方 pill 高度约 40dp，
 * `TextField` 自带 56dp 最小高度与指示器，无法做到官方观感。
 */
@Composable
private fun SearchTopBar(
    query: String,
    isSearching: Boolean,
    showCancel: Boolean,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    onScan: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
                .clip(RoundedCornerShape(50))
                // 官方搜索框底色：浅灰（surfaceVariant 自适应深浅色）。
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(modifier = Modifier.weight(1f)) {
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Search,
                    ),
                    keyboardActions = KeyboardActions(
                        onSearch = { onSubmit() },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { onFocusChanged(it.isFocused) },
                )
                if (query.isEmpty()) {
                    Text(
                        text = stringResource(R.string.search_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            if (isSearching) {
                Spacer(modifier = Modifier.width(8.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (query.isNotEmpty()) {
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = onClear,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.search_clear),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            } else {
                // 搜索框尾部集成「扫一扫」（官方：搜索框右端扫码入口）。
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = onScan,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.QrCodeScanner,
                        contentDescription = stringResource(R.string.scan_open),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        AnimatedVisibility(visible = showCancel) {
            Text(
                text = stringResource(R.string.search_cancel),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClick = onCancel)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * 联想下拉（官方输入态）：关键词补全行（allMatch）+ 单曲/歌手/专辑/歌单分区。
 *
 * 点击语义照搬官方：单曲行即点即播；关键词/歌手/专辑/歌单行以该词提交搜索。
 */
@Composable
internal fun SuggestionBlock(
    query: String,
    suggestions: SearchSuggestBody?,
    onPlaySong: (Track) -> Unit,
    onSubmitKeyword: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        // 关键词补全（官方「猜你想搜」行：放大镜 + 关键词）。
        suggestions?.allMatch?.take(KeywordLimit)?.forEach { match ->
            SuggestionKeywordRow(text = match.keyword, onClick = { onSubmitKeyword(match.keyword) })
        }

        // 单曲联想：封面 + 「歌名 - 歌手」，即点即播。
        val songs = suggestions?.songs.orEmpty()
        if (songs.isNotEmpty()) {
            SuggestionSectionTitle(text = stringResource(R.string.search_section_songs))
            songs.take(SongLimit).forEach { track ->
                SuggestionMediaRow(
                    coverUrl = track.album.picUrl,
                    text = "${track.name} - ${track.artistNames}",
                    onClick = { onPlaySong(track) },
                )
            }
        }

        // 歌手联想：圆形头像 + 歌手名。
        val artists = suggestions?.artists.orEmpty()
        if (artists.isNotEmpty()) {
            SuggestionSectionTitle(text = stringResource(R.string.search_section_artists))
            artists.take(MediaLimit).forEach { artist ->
                SuggestionMediaRow(
                    coverUrl = artist.picUrl,
                    roundCover = true,
                    text = artist.name,
                    onClick = { onSubmitKeyword(artist.name) },
                )
            }
        }

        // 专辑联想：封面 + 「专辑名 - 歌手」。
        val albums = suggestions?.albums.orEmpty()
        if (albums.isNotEmpty()) {
            SuggestionSectionTitle(text = stringResource(R.string.search_section_albums))
            albums.take(MediaLimit).forEach { album ->
                SuggestionMediaRow(
                    coverUrl = album.picUrl,
                    text = album.name + (album.artist?.name?.let { " - $it" } ?: ""),
                    onClick = { onSubmitKeyword(album.name) },
                )
            }
        }

        // 歌单联想：封面 + 歌单名。
        val playlists = suggestions?.playlists.orEmpty()
        if (playlists.isNotEmpty()) {
            SuggestionSectionTitle(text = stringResource(R.string.search_section_playlists))
            playlists.take(MediaLimit).forEach { playlist ->
                SuggestionMediaRow(
                    coverUrl = playlist.coverURL,
                    text = playlist.name,
                    onClick = { onSubmitKeyword(playlist.name) },
                )
            }
        }

        // 无任何联想时兜底：直接搜索当前输入（官方同样保留原始词入口）。
        if (suggestions?.allMatch.isNullOrEmpty() &&
            songs.isEmpty() &&
            artists.isEmpty() &&
            albums.isEmpty() &&
            playlists.isEmpty()
        ) {
            SuggestionKeywordRow(text = query, onClick = { onSubmitKeyword(query) })
        }
    }
}

/** 联想关键词行（放大镜 + 关键词；官方「猜你想搜」）。 */
@Composable
private fun SuggestionKeywordRow(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 12.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 联想分区标题（官方：灰色小标题，如「单曲」「歌手」）。 */
@Composable
private fun SuggestionSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

/** 联想媒体行：封面（歌手为圆形）+ 文本。 */
@Composable
private fun SuggestionMediaRow(
    coverUrl: String?,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    roundCover: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(if (roundCover) CircleShape else RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (!coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 12.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 联想分区展示条数（官方下拉约 6-8 条/区）。 */
private const val KeywordLimit = 8
private const val SongLimit = 8
private const val MediaLimit = 6
