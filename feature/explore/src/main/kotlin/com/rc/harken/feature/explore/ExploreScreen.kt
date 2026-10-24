// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 发现页屏幕（T19 `ExploreScreen.kt`，iOS `ExploreView` 1:1 移植）。
//
// 顶部 27 个分类 chips（横滑）；「排行榜」分类渲染 [ToplistGrid]（110 封面 + 前三名
// 预览），其余分类渲染 [PlaylistGrid]（自适应 140dp 双列 + 播放量角标 + 悬浮播放）。
// 滚动接近底部自动 [ExploreViewModel.loadMore]（iOS `Color.clear.onAppear` 触发）。
// 数据经 [ExploreUiState] 单向驱动；详情导航走 [navController]。

package com.rc.harken.feature.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
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
import com.rc.harken.design.component.ErrorStateView
import com.rc.harken.design.component.staggeredAppearance
import com.rc.harken.design.composition.LocalNeteaseApi
import com.rc.harken.design.composition.LocalPlaybackCommands
import com.rc.harken.design.navigation.Destinations
import com.rc.harken.design.theme.GlassTopAppBar
import com.rc.harken.design.theme.Layout
import com.rc.harken.feature.explore.components.CategoryChips
import com.rc.harken.feature.explore.components.PlaylistGrid
import com.rc.harken.feature.explore.components.ToplistGrid
import com.rc.harken.net.model.Formatters
import kotlinx.coroutines.launch

/**
 * 发现页主屏幕。
 *
 * @param navController 覆盖层导航控制器，用于跳转歌单详情。
 */
@Composable
fun ExploreScreen(
    navController: NavHostController,
    showTopBar: Boolean = true,
) {
    val api = LocalNeteaseApi.current
        ?: error("LocalNeteaseApi 必须在 HarkenApp 提供")
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

    // 顶部结构重做：玻璃顶栏（「发现」标题，M3 TopAppBar 自带状态栏 insets）
    // + 固定分类胶囊行（不随内容滚走），下方内容各自滚动 —— 对齐 App 的液态玻璃
    // 设计语言，且任何分类下都不会与系统状态栏重叠（旧实现仅排行榜分支有留白）。
    Column(modifier = Modifier.fillMaxSize()) {
        ExploreHeader(
            categories = uiState.categories,
            selected = uiState.selectedCategory,
            onSelect = { viewModel.select(it) },
            showTopBar = showTopBar,
        )
        if (uiState.selectedCategory == CATEGORY_TOPLISTS) {
            // iOS: ToplistGrid(toplists:).padding(.horizontal, contentInset)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 300.dp),
                state = toplistGridState,
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = Layout.contentInset,
                    end = Layout.contentInset,
                    top = 8.dp,
                    bottom = 8.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                itemsIndexed(uiState.toplists, key = { _, item -> item.id }) { _, toplist ->
                    ToplistRowItem(
                        toplist = toplist,
                        onOpen = onOpenPlaylist,
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }, key = "clearance") {
                    Spacer(Modifier.height(Layout.fullChromeClearance + Layout.bottomGestureInset()))
                }
            }
        } else {
            // iOS: CardGrid { CoverCardBody(...) } + 底部加载态 / 触发位。
            // 左右白边 10dp(用户反馈原 24dp 过宽,内容应向两边扩展)。
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 140.dp),
                state = playlistGridState,
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 10.dp,
                    end = 10.dp,
                    top = 8.dp,
                    bottom = 8.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                itemsIndexed(uiState.playlists, key = { _, playlist -> playlist.id }) { index, playlist ->
                    com.rc.harken.design.component.CoverCard(
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
                                .fillMaxWidth()
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
                    Spacer(Modifier.height(Layout.fullChromeClearance + Layout.bottomGestureInset()))
                }
            }
        }
    }
}

/**
 * 发现页顶部：玻璃顶栏（「发现」标题，M3 TopAppBar 自带状态栏 insets，永不与
 * 系统状态栏重叠）+ 固定分类胶囊行（横滑切换分类，不随内容滚动）。
 */
@Composable
private fun ExploreHeader(
    categories: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    showTopBar: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (showTopBar) {
            GlassTopAppBar(
                title = { Text(text = stringResource(R.string.explore_title)) },
                expandedHeight = 52.dp,
            )
        } else {
            // 无顶栏模式(首页 Tab):状态栏避让 + 受控 12dp 间距,内容整体上移。
            Spacer(
                modifier = Modifier
                    .statusBarsPadding()
                    .height(12.dp),
            )
        }
        CategoryChips(
            categories = categories,
            selected = selected,
            onSelect = onSelect,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
}

/** 是否仍可加载更多（派生状态辅助，避免内联复杂表达式）。 */
private fun uiStateHasMore(state: ExploreUiState): Boolean = state.hasMore && !state.isLoading

/** 单行榜单（与 `ToplistGrid` 单元格同构，供排行榜 Tab 复用）。 */
@Composable
private fun ToplistRowItem(
    toplist: com.rc.harken.net.model.ToplistItem,
    onOpen: (Long) -> Unit,
) {
    ToplistGrid(
        toplists = listOf(toplist),
        onOpen = onOpen,
        modifier = Modifier.fillMaxWidth(),
    )
}

