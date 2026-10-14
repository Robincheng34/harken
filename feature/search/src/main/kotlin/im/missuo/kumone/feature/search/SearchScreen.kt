// Ported from kumone by missuo (LGPL-3.0)
//
// 搜索页屏幕（P1-1 收尾，T20 `SearchScreen.kt`，iOS `SearchView` 1:1 移植）。
//
// 结构：搜索框（边输入边联想）→ 查询非空时展示 [SearchSegmented] 五段切换 →
// [SearchResultSections] 按当前 Tab 呈现结果分区。查询为空时展示引导页
// （iOS `emptySearchPrompt`）。
//
// 惰性加载：切换 Tab 时由 [SearchViewModel.selectTab] 触发对应类型检索；
// `loadedTabs` 保证不重复请求（iOS `load(tab:)`）。

package im.missuo.kumone.feature.search

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import im.missuo.kumone.design.composition.LocalMusicController
import im.missuo.kumone.design.composition.LocalNeteaseApi
import im.missuo.kumone.design.composition.LocalPlaybackCommands
import im.missuo.kumone.design.navigation.Destinations
import im.missuo.kumone.feature.search.components.SearchResultSections
import im.missuo.kumone.feature.search.components.SearchSegmented
import im.missuo.kumone.net.model.SearchSuggestBody
import im.missuo.kumone.net.model.Track
import im.missuo.kumone.playback.PlaySource
import im.missuo.kumone.player.PlayerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 搜索页主屏幕。
 *
 * @param navController 覆盖层导航控制器，用于跳转歌手/专辑/歌单详情。
 */
@Composable
fun SearchScreen(navController: NavHostController) {
    val api = LocalNeteaseApi.current
        ?: error("LocalNeteaseApi 必须在 KumoneApp 提供")
    val commands = LocalPlaybackCommands.current
    val scope = rememberCoroutineScope()

    val viewModel = remember(api) { SearchViewModel(api) }
    // 页面销毁时取消 ViewModel 内部协程（原 bug：自建 scope 永不取消，收集器常驻泄漏）。
    DisposableEffect(Unit) { onDispose { viewModel.cancel() } }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 当前播放曲（单曲列表高亮）。
    val controller = LocalMusicController.current
    val playerState by (controller?.state ?: remember { MutableStateFlow(PlayerState()) })
        .collectAsStateWithLifecycle()
    val currentTrackId: Long? = playerState.currentTrack?.id

    Column(modifier = Modifier.fillMaxSize()) {
        SearchBar(
            query = uiState.query,
            isSearching = uiState.isSearching,
            onQueryChange = viewModel::setQuery,
            onSearch = viewModel::search,
            onClear = { viewModel.setQuery("") },
        )

        if (!uiState.showSegmented) {
            EmptySearchPrompt(modifier = Modifier.fillMaxSize())
            return@Column
        }

        Spacer(modifier = Modifier.height(4.dp))

        SearchSegmented(
            selected = uiState.tab,
            onSelect = viewModel::selectTab,
        )

        Spacer(modifier = Modifier.height(8.dp))

        when {
            uiState.isSearching && uiState.currentEmpty() ->
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }

            uiState.currentEmpty() ->
                EmptySearchResult(
                    message = uiState.error ?: stringResource(R.string.search_no_result),
                    modifier = Modifier.fillMaxSize(),
                )

            else -> {
                // 有联想但当前 Tab 尚未产出结果时，先给联想（iOS `.searchable` 下拉）。
                val suggestions = uiState.suggestions
                if (suggestions != null && uiState.loadedTabs.isEmpty()) {
                    SuggestionBlock(
                        suggestions = suggestions,
                        onOpenArtist = { navController.navigate(Destinations.artistDetail(it)) },
                        onOpenAlbum = { navController.navigate(Destinations.albumDetail(it)) },
                        onOpenPlaylist = { navController.navigate(Destinations.playlistDetail(it)) },
                        onPlaySong = { track ->
                            val list = uiState.songs
                            scope.launch {
                                commands?.playFrom(list, track.id, PlaySource.PLAYLIST, null)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    SearchResultSections(
                        uiState = uiState,
                        currentTrackId = currentTrackId,
                        onPlayTrack = { track, _ ->
                            val list = uiState.songs
                            scope.launch {
                                commands?.playFrom(list, track.id, PlaySource.PLAYLIST, null)
                            }
                        },
                        onOpenArtist = { navController.navigate(Destinations.artistDetail(it)) },
                        onOpenAlbum = { navController.navigate(Destinations.albumDetail(it)) },
                        onOpenPlaylist = { navController.navigate(Destinations.playlistDetail(it)) },
                        onSelectTab = viewModel::selectTab,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/** 搜索框（iOS `.searchable` 的安卓等价：行内输入 + 搜索按钮）。 */
@Composable
private fun SearchBar(
    query: String,
    isSearching: Boolean,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text(stringResource(R.string.search_hint)) },
            singleLine = true,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            trailingIcon = {
                if (isSearching) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else if (query.isNotEmpty()) {
                    IconButton(onClick = onClear, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.search_clear),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            },
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                unfocusedIndicatorColor = MaterialTheme.colorScheme.outlineVariant,
            ),
            modifier = Modifier
                .weight(1f)
                .height(56.dp),
        )
        TextButton(onClick = onSearch) {
            Text(stringResource(R.string.search_action))
        }
    }
}

/** 查询为空时的引导页（iOS `emptySearchPrompt`）。 */
@Composable
private fun EmptySearchPrompt(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.search_empty_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.search_empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

/** 当前 Tab 无结果（iOS `EmptyStateView(icon:"magnifyingglass", title:"没有找到相关结果")`）。 */
@Composable
private fun EmptySearchResult(
    message: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(40.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 联想建议行（iOS `.searchable` 系统建议的安卓内联等价物）。 */
@Composable
internal fun SuggestionRow(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
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
        )
    }
}

/** 联想建议分区（iOS `.searchable` 下拉；仅在「有联想但当前 Tab 尚无结果」时展示）。 */
@Composable
internal fun SuggestionBlock(
    suggestions: SearchSuggestBody,
    onOpenArtist: (Long) -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onPlaySong: (Track) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        suggestions.songs?.take(8)?.forEach { track ->
            SuggestionRow(text = "${track.name} - ${track.artistNames}") { onPlaySong(track) }
        }
        suggestions.artists?.take(6)?.forEach { artist ->
            SuggestionRow(text = artist.name) { onOpenArtist(artist.id) }
        }
        suggestions.albums?.take(6)?.forEach { album ->
            SuggestionRow(text = album.name) { onOpenAlbum(album.id) }
        }
        suggestions.playlists?.take(6)?.forEach { playlist ->
            SuggestionRow(text = playlist.name) { onOpenPlaylist(playlist.id) }
        }
    }
}