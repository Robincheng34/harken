// Ported from kumone by missuo (LGPL-3.0)
//
// 首页屏幕（T18 `HomeScreen.kt`，iOS `HomeView` 1:1 移植）。
//
// 结构（iOS `loadedBody`）：功能卡横滑（仅登录态）→ 推荐歌单 shelf（前 12，逐个入场）
// → 雷达歌单 shelf（登录态）→ 排行榜 shelf → 新碟上架 → 推荐歌手 → 底部浮栏净空。
// 加载态为骨架屏（三张 230×132 功能卡 + 两个骨架 shelf）；失败态为 [ErrorStateView]。
// 数据由 [HomeViewModel] 经 [HomeUiState] 驱动；播放走 [LocalPlaybackCommands]，
// 详情导航走 [navController]。P0 阶段未就绪入口（每日推荐/心动模式）点击后 Toast
// 「敬请期待」（架构文档 §9.6），P1 批（T30）统一开放。

@file:OptIn(ExperimentalMaterial3Api::class)

package im.missuo.kumone.feature.home

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import im.missuo.kumone.design.component.CoverCard
import im.missuo.kumone.design.component.ErrorStateView
import im.missuo.kumone.design.component.SectionHeader
import im.missuo.kumone.design.component.SkeletonCover
import im.missuo.kumone.design.component.SkeletonLine
import im.missuo.kumone.design.component.staggeredAppearance
import im.missuo.kumone.design.composition.LocalIsLoggedIn
import im.missuo.kumone.design.composition.LocalNeteaseApi
import im.missuo.kumone.design.composition.LocalPlaybackCommands
import im.missuo.kumone.design.composition.LocalToastState
import im.missuo.kumone.design.theme.Layout
import im.missuo.kumone.feature.home.components.ArtistShelf
import im.missuo.kumone.feature.home.components.FunctionCards
import im.missuo.kumone.feature.home.components.HomeShelf
import im.missuo.kumone.feature.home.components.NewAlbumShelf
import im.missuo.kumone.design.component.PlaylistShelf
import im.missuo.kumone.design.component.ToplistShelf
import im.missuo.kumone.net.model.Formatters
import kotlinx.coroutines.launch

/**
 * 首页主屏幕。
 *
 * @param navController 覆盖层导航控制器，用于跳转歌单/专辑/歌手详情。
 * @param bottomClearance 列表尾部净空：有曲目（迷你播放条占位）时为
 *   [Layout.fullChromeClearance]，否则为 [Layout.tabBarClearance]。
 */
@Composable
fun HomeScreen(
    navController: NavHostController,
    bottomClearance: Dp = Layout.fullChromeClearance,
) {
    val api = LocalNeteaseApi.current
        ?: error("LocalNeteaseApi 必须在 KumoneApp 提供")
    val commands = LocalPlaybackCommands.current
    val toast = LocalToastState.current
    val isLoggedIn by LocalIsLoggedIn.current.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    val viewModel = remember(api) { HomeViewModel(api) }
    // 页面销毁时取消 ViewModel 内部协程（原 bug：自建 scope 永不取消，收集器常驻泄漏）。
    DisposableEffect(Unit) { onDispose { viewModel.cancel() } }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val ptrState = rememberPullToRefreshState()

    LaunchedEffect(isLoggedIn) { viewModel.load(isLoggedIn) }

    val comingSoon = stringResource(R.string.home_coming_soon)
    val onPlaylist: (Long) -> Unit = { id ->
        scope.launch { commands?.playPlaylist(id) }
    }
    val onAlbum: (Long) -> Unit = { id ->
        val name = uiState.newAlbums.firstOrNull { it.id == id }?.name ?: ""
        scope.launch { commands?.playAlbum(id, name) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            // iOS: case .idle, .loading -> loadingBody
            uiState.isLoading &&
                uiState.recommendPlaylists.isEmpty() &&
                uiState.newAlbums.isEmpty() -> HomeLoadingBody()

            // iOS: case .error -> ErrorStateView（可重试）
            uiState.error != null &&
                uiState.recommendPlaylists.isEmpty() &&
                uiState.newAlbums.isEmpty() -> ErrorStateView(
                message = uiState.error ?: stringResource(R.string.home_load_failed),
                onRetry = { viewModel.reload(isLoggedIn) },
            )

            // iOS: case .loaded -> loadedBody，外层包 Material3 下拉刷新。
            else -> PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = { viewModel.refresh(isLoggedIn) },
                state = ptrState,
                modifier = Modifier.fillMaxSize(),
                indicator = {
                    KumoneAppIconIndicator(
                        state = ptrState,
                        isRefreshing = uiState.isRefreshing,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 24.dp),
                    )
                },
                content = {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = Layout.contentInset - 8.dp),
                        // 分区间距：12dp（LazyColumn）+ SectionHeader 上内边距 12dp = 视觉
                        // 间隔 24dp。原 34dp 叠加 header 内边距后视觉间隔 46dp，用户反馈
                        // 「推荐歌单底部与排行榜顶部上下间隔太大」。
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // 功能卡（仅登录态；iOS `if account.isLoggedIn`）。
                        if (isLoggedIn) {
                            item(key = "feature-cards") {
                                FunctionCards(
                                    onDaily = { toast.show(comingSoon) }, // P1（T30）开放
                                    onFM = { commands?.startFM() },
                                    onHeartbeat = { toast.show(comingSoon) }, // P1（T30）开放
                                    dailyCover = uiState.dailyFirstCover,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }

                        if (uiState.recommendPlaylists.isNotEmpty()) {
                            item(key = "recommend") {
                                PlaylistShelf(
                                    title = stringResource(R.string.home_recommend_playlists),
                                    items = uiState.recommendPlaylists.take(12), // iOS: prefix(12)
                                    onOpen = { navController.navigate(playlistDetailRoute(it)) },
                                    onPlay = onPlaylist,
                                    staggered = true,
                                )
                            }
                        }

                        if (uiState.radarPlaylists.isNotEmpty()) {
                            item(key = "radar") {
                                HomeShelf(
                                    title = stringResource(R.string.home_radar_playlists),
                                ) {
                                    itemsIndexed(
                                        uiState.radarPlaylists,
                                        key = { _, radar -> radar.id },
                                    ) { _, radar ->
                                        CoverCard(
                                            imageUrl = Formatters.resizedImageURL(radar.coverURL, 384),
                                            contentDescription = radar.title,
                                            title = radar.title,
                                            subtitle = radar.subtitle,
                                            size = Layout.cardSize,
                                            onPlay = { onPlaylist(radar.id) },
                                            onClick = { navController.navigate(playlistDetailRoute(radar.id)) },
                                        )
                                    }
                                }
                            }
                        }

                        if (uiState.toplists.isNotEmpty()) {
                            item(key = "toplists") {
                                ToplistShelf(
                                    title = stringResource(R.string.home_toplists),
                                    items = uiState.toplists,
                                    onOpen = { navController.navigate(playlistDetailRoute(it)) },
                                )
                            }
                        }

                        if (uiState.newAlbums.isNotEmpty()) {
                            item(key = "new-albums") {
                                NewAlbumShelf(
                                    items = uiState.newAlbums,
                                    onOpen = { navController.navigate(albumDetailRoute(it)) },
                                    onPlay = onAlbum,
                                )
                            }
                        }

                        if (uiState.artists.isNotEmpty()) {
                            item(key = "artists") {
                                ArtistShelf(
                                    items = uiState.artists,
                                    onOpen = { navController.navigate(artistDetailRoute(it)) },
                                )
                            }
                        }

                        // 底部悬浮 chrome 净空：有迷你播放条时 = 迷你条 52 + 间距 8 + TabBar 64 + 6 + 12；
                        // 无曲目时迷你条不占位，只需 TabBar 净空，否则列表末尾悬着 60dp 死白。
                        item(key = "clearance") {
                            Spacer(Modifier.height(bottomClearance))
                        }
                    }
                },
            )
    }
}

}

/** 加载态骨架（iOS `loadingBody`：三张 230×132 功能卡 + 两个骨架 shelf）。 */
@Composable
private fun HomeLoadingBody() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Layout.contentInset),
        verticalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(3) {
                SkeletonCover(
                    modifier = Modifier
                        .width(230.dp) // iOS: FeatureCard 230×132
                        .height(132.dp),
                )
            }
        }
        repeat(2) { HomeSkeletonShelf() }
    }
}

/** 单个骨架 shelf（分区头占位 + 三张 160dp 封面卡占位）。 */
@Composable
private fun HomeSkeletonShelf() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SkeletonLine(
            modifier = Modifier
                .width(96.dp)
                .height(16.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(3) {
                Column(
                    modifier = Modifier.width(Layout.cardSize),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SkeletonCover(modifier = Modifier.width(Layout.cardSize).aspectRatio(1f))
                    SkeletonLine(
                        modifier = Modifier
                            .width(Layout.cardSize)
                            .height(14.dp),
                    )
                    SkeletonLine(
                        modifier = Modifier
                            .width(Layout.cardSize - 40.dp)
                            .height(12.dp),
                    )
                }
            }
        }
    }
}

/**
 * 自定义下拉刷新指示器：仿网易云音乐官方——下拉时顶部出现 App 图标（黑底金色螺旋），
 * 随拖拽进度旋转，刷新时持续旋转。基于 Material3 [PullToRefreshBox] 的 indicator 槽位。
 */
@Composable
private fun KumoneAppIconIndicator(
    state: PullToRefreshState,
    isRefreshing: Boolean,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "refreshSpin")
    val spin by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spin",
    )
    val distanceFraction = state.distanceFraction.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .size(42.dp)
            .alpha(distanceFraction)
            .clip(CircleShape)
            .background(Color.Black)
            .graphicsLayer {
                rotationZ = if (isRefreshing) spin else distanceFraction * 360f
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_app_logo),
            contentDescription = null,
            modifier = Modifier.size(30.dp),
        )
    }
}
