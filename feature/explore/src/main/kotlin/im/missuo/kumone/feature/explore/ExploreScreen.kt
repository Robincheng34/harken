// Ported from kumone by missuo (LGPL-3.0)
//
// 发现页屏幕（T19 `ExploreScreen.kt`，iOS `ExploreView` 1:1 移植）。
//
// 顶部 27 个分类 chips（横滑）；「排行榜」分类渲染 [ToplistGrid]（110 封面 + 前三名
// 预览），其余分类渲染 [PlaylistGrid]（自适应 140dp 双列 + 播放量角标 + 悬浮播放）。
// 滚动接近底部自动 [ExploreViewModel.loadMore]（iOS `Color.clear.onAppear` 触发）。
// 数据经 [ExploreUiState] 单向驱动；详情导航走 [navController]。

package im.missuo.kumone.feature.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import im.missuo.kumone.design.component.ErrorStateView
import im.missuo.kumone.design.component.staggeredAppearance
import im.missuo.kumone.design.composition.LocalNeteaseApi
import im.missuo.kumone.design.composition.LocalPlaybackCommands
import im.missuo.kumone.design.navigation.Destinations
import im.missuo.kumone.design.theme.Layout
import im.missuo.kumone.feature.explore.components.CategoryChips
import im.missuo.kumone.feature.explore.components.PlaylistGrid
import im.missuo.kumone.feature.explore.components.ToplistGrid
import im.missuo.kumone.net.model.Formatters
import kotlinx.coroutines.launch

/**
 * 发现页主屏幕。
 *
 * @param navController 覆盖层导航控制器，用于跳转歌单详情。
 */
@Composable
fun ExploreScreen(navController: NavHostController) {
    val api = LocalNeteaseApi.current
        ?: error("LocalNeteaseApi 必须在 KumoneApp 提供")
    val commands = LocalPlaybackCommands.current
    val scope = rememberCoroutineScope()

    val viewModel = androidx.compose.runtime.remember(api) { ExploreViewModel(api) }
    // 页面销毁时取消 ViewModel 内部协程（原 bug：自建 scope 永不取消，
    // DataStore/网络收集器常驻泄漏）。
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { viewModel.cancel() }
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.load() }

    val onOpenPlaylist: (Long) -> Unit = { navController.navigate(Destinations.playlistDetail(it)) }
    val onPlayPlaylist: (Long) -> Unit = { id ->
        scope.launch { commands?.playPlaylist(id) }
    }

    // 空列表 + 错误：展示错误态（iOS 静默留空，此处提供重试入口）。
    if (uiState.playlists.isEmpty() &&
        uiState.toplists.isEmpty() &&
        uiState.error != null &&
        !uiState.isLoading
    ) {
        ErrorStateView(
            message = uiState.error ?: stringResource(R.string.explore_load_failed),
            onRetry = { viewModel.select(uiState.selectedCategory) },
        )
        return
    }

    // 原 bug：排行榜与歌单两分支共用同一 rememberLazyGridState()，切换分类时
    // 滚动位置 / 布局信息互相污染。各自独立。
    val toplistGridState = rememberLazyGridState()
    val playlistGridState = rememberLazyGridState()
    // 滚动接近底部自动加载下一页（iOS `Color.clear.onAppear` 触发等价物）。
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = playlistGridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = playlistGridState.layoutInfo.totalItemsCount
            uiStateHasMore(uiState) && lastVisible >= 0 && lastVisible >= total - 3
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) viewModel.loadMore()
    }

    if (uiState.selectedCategory == CATEGORY_TOPLISTS) {
        // iOS: ToplistGrid(toplists:).padding(.horizontal, contentInset)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 300.dp),
            state = toplistGridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = Layout.contentInset,
                end = Layout.contentInset,
                top = 8.dp,
                bottom = 8.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "chips") {
                CategoryChips(
                    categories = uiState.categories,
                    selected = uiState.selectedCategory,
                    onSelect = { viewModel.select(it) },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            itemsIndexed(uiState.toplists, key = { _, item -> item.id }) { _, toplist ->
                ToplistRowItem(
                    toplist = toplist,
                    onOpen = onOpenPlaylist,
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "clearance") {
                Spacer(Modifier.height(Layout.fullChromeClearance))
            }
        }
        return
    }

    // iOS: CardGrid { CoverCardBody(...) } + 底部加载态 / 触发位。
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp), // 架构文档 T19：minWidth 140dp
        state = playlistGridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = Layout.contentInset,
            end = Layout.contentInset,
            top = 8.dp,
            bottom = 8.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "chips") {
            CategoryChips(
                categories = uiState.categories,
                selected = uiState.selectedCategory,
                onSelect = { viewModel.select(it) },
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        itemsIndexed(uiState.playlists, key = { _, playlist -> playlist.id }) { index, playlist ->
            im.missuo.kumone.design.component.CoverCard(
                imageUrl = Formatters.resizedImageURL(playlist.coverURL, 384),
                contentDescription = playlist.name,
                title = playlist.name,
                subtitle = playlist.copywriter,
                size = null,
                playCount = playlist.playCount,
                onPlay = { onPlayPlaylist(playlist.id) },
                onClick = { onOpenPlaylist(playlist.id) },
                modifier = Modifier.staggeredAppearance(index = index % 10),
            )
        }

        // iOS: isLoading -> ProgressView；否则 hasMore 时空位触发加载。
        if (uiState.isLoading) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "loading") {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }, key = "clearance") {
            Spacer(Modifier.height(Layout.fullChromeClearance))
        }
    }
}

/** 是否仍可加载更多（派生状态辅助，避免内联复杂表达式）。 */
private fun uiStateHasMore(state: ExploreUiState): Boolean = state.hasMore && !state.isLoading

/** 单行榜单（与 `ToplistGrid` 单元格同构，供排行榜 Tab 复用）。 */
@Composable
private fun ToplistRowItem(
    toplist: im.missuo.kumone.net.model.ToplistItem,
    onOpen: (Long) -> Unit,
) {
    ToplistGrid(
        toplists = listOf(toplist),
        onOpen = onOpen,
        modifier = Modifier.fillMaxWidth(),
    )
}
