// Ported from kumone by missuo (LGPL-3.0)
//
// 应用根可组合（架构文档 §3 `ui/KumoneApp.kt`）。
//
// 结构：
//  - `TabsScreen`：当前 Tab 内容 + 迷你播放条（52dp）+ 悬浮 GlassTabBar。
//  - 覆盖层 `NavHost`：详情 / 登录 / 全屏播放 / 设置 / P1 页面叠加其上，不影响 Tab 状态。
//  - 未登录时自动升起 `LoginSheet`。
//  - 下发 MusicController / NeteaseApi / SettingsRepository / PlaybackCommands /
//    登录态 / Toast 六个 CompositionLocal。

package im.missuo.kumone.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.toIntSize
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import im.missuo.kumone.design.component.ToastCenter
import im.missuo.kumone.design.component.rememberToastState
import im.missuo.kumone.design.composition.LocalIsLoggedIn
import im.missuo.kumone.design.composition.LocalMusicController
import im.missuo.kumone.design.composition.LocalNeteaseApi
import im.missuo.kumone.design.composition.LocalOnLoginConfirmed
import im.missuo.kumone.design.composition.LocalOnLoggedOut
import im.missuo.kumone.design.composition.LocalOpenNowPlaying
import im.missuo.kumone.design.composition.LocalPlaybackCommands
import im.missuo.kumone.design.composition.LocalSettingsRepository
import im.missuo.kumone.design.composition.LocalToastState
import im.missuo.kumone.design.navigation.Destinations
import im.missuo.kumone.design.theme.Layout
import im.missuo.kumone.design.theme.KumoneTheme
import im.missuo.kumone.design.theme.QUICK_MILLIS
import im.missuo.kumone.design.theme.SMOOTH_MILLIS
import im.missuo.kumone.design.theme.STANDARD_MILLIS
import im.missuo.kumone.di.AppGraph
import im.missuo.kumone.di.LoginState
import im.missuo.kumone.feature.detail.detailGraph
import im.missuo.kumone.feature.explore.ExploreScreen
import im.missuo.kumone.feature.explore.exploreGraph
import im.missuo.kumone.feature.fm.FmScreen
import im.missuo.kumone.feature.fm.fmGraph
import im.missuo.kumone.feature.home.HomeScreen
import im.missuo.kumone.feature.home.homeGraph
import im.missuo.kumone.feature.library.LibraryScreen
import im.missuo.kumone.feature.library.libraryGraph
import im.missuo.kumone.feature.login.LoginSheet
import im.missuo.kumone.feature.login.loginGraph
import im.missuo.kumone.feature.player.MiniPlayerBar
import im.missuo.kumone.feature.player.playerGraph
import im.missuo.kumone.feature.search.SearchScreen
import im.missuo.kumone.feature.search.searchGraph
import im.missuo.kumone.feature.settings.settingsGraph
import im.missuo.kumone.storage.settings.AppAppearance
import im.missuo.kumone.storage.settings.AppSettings
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * 实时折射捕获间隔（纳秒）。IT之家液态玻璃的标志是「内容滑过底栏时产生扭曲」，
 * 因此逐帧把 behindLayer 截成底栏条带喂给镜片；节流到约 15fps 以平衡折射实时性
 * 与 GPU 回读开销（见 [TabsScreen]）。
 */
private const val LIVE_CAPTURE_INTERVAL_NANOS = 66_000_000L

/**
 * 是否启用「镜片实时折射」的整屏 GPU→CPU 回读。
 *
 * 实测（OnePlus7TPro，滚动信息流）：开启时 50% 掉帧、帧时中位数 31ms，而 GPU
 * 百分位仅 2ms——瓶颈是主线程上每 66ms 一次的整屏（1440×3120 ≈ 18MB）
 * `behindLayer.toImageBitmap()` 同步回读 + 位图拷贝，与 GPU 无关。用户反馈
 * 「app 滑动的时候肉眼可见的卡顿掉帧」即由此而来。
 *
 * 关闭后：镜片不再有折射源（选中态只剩红色图标/文案），但「真·磨砂」背景
 * （[TabsScreen] 里 drawLayer + BlurEffect，纯 GPU、无回读）完全保留——
 * 玻璃观感的主要来源仍在，滚动则回到 60fps。
 */
private const val LENS_LIVE_CAPTURE_ENABLED = false

/**
 * 底栏「真·磨砂」背景的高斯模糊半径（px）。把整屏 behindLayer 平移到位后经
 * RenderEffect 模糊，垫在悬浮底栏之下，形成 IT之家风格的毛玻璃底（见 [TabsScreen]）。
 */
private const val FROST_BLUR_RADIUS = 26f

/** 镜片诊断日志 tag（真机 `adb logcat -s LiquidGlassLens` 观察捕获结果）。 */
private const val TAG_LENS = "LiquidGlassLens"

/**
 * 应用根。由 [MainActivity] 调用，包裹主题与全部 CompositionLocal。
 */
@Composable
fun KumoneApp(graph: AppGraph) {
    val appScope = rememberCoroutineScope()
    val toastState = rememberToastState()

    // 外观设置（原 bug：KumoneTheme 未传 darkTheme，「外观」设置从未生效）。
    // 在主题外收集完整 AppSettings，按 appearance 解析深浅色：
    // AUTO → 跟随系统；LIGHT/DARK → 强制。
    val settings by graph.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val darkTheme = when (settings?.appearance) {
        AppAppearance.LIGHT -> false
        AppAppearance.DARK -> true
        else -> isSystemInDarkTheme()
    }

    KumoneTheme(darkTheme = darkTheme) {
        CompositionLocalProvider(
            LocalMusicController provides graph.musicController,
            LocalNeteaseApi provides graph.neteaseApi,
            LocalSettingsRepository provides graph.settings,
            LocalPlaybackCommands provides graph.playbackCommands,
            LocalIsLoggedIn provides graph.isLoggedIn,
            LocalToastState provides toastState,
            LocalOnLoginConfirmed provides {
                appScope.launch {
                    graph.bootstrap()
                }
            },
            // 登出成功后刷新全局登录态（原 bug：AppGraph.onLoggedOut 零调用）。
            LocalOnLoggedOut provides {
                graph.onLoggedOut()
            },
        ) {
            AppContent(graph)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppContent(graph: AppGraph) {
    val overlayNav: NavHostController = rememberNavController()
    // rememberSaveable：旋转/进程重建后保留选中 Tab（enum 可序列化）。
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.HOME) }
    val toastState = LocalToastState.current

    // 底部导航栏自定义配置：按设置页保存的顺序渲染可见 Tab（未配置时为全部默认顺序）。
    val settingsRepository = LocalSettingsRepository.current
    val storedSettings by remember(settingsRepository) {
        settingsRepository?.settings ?: flowOf<AppSettings?>(null)
    }.collectAsStateWithLifecycle(initialValue = null)
    val visibleTabs = remember(storedSettings) {
        storedSettings?.tabOrder
            ?.mapNotNull { key -> AppTab.entries.firstOrNull { it.name == key } }
            ?.distinct()
            ?.ifEmpty { AppTab.entries.toList() }
            ?: AppTab.entries.toList()
    }
    // 当前选中的 Tab 被隐藏时，回落到第一个可见 Tab。
    LaunchedEffect(visibleTabs) {
        if (selectedTab !in visibleTabs) selectedTab = visibleTabs.first()
    }

    // 播放层提示（试听 / 已使用第三方音源）：由常驻 MusicController 判定并上抛
    // 资源 ID，此处统一解析并弹（iOS PlayerService.swift:1093-1100）。挂在根
    // 可组合上，因此任意页面、播放页未打开时都能弹出。
    // 资源 ID 由 MusicController（非 Composable 层）上抛，此处在可组合作用域内
    // 解析成文案；`stringResource` 不能在 LaunchedEffect 的协程体内调用。
    val context = LocalContext.current
    LaunchedEffect(graph.musicController) {
        graph.musicController.playerToastEvent.collect { event ->
            val text = if (event.args.isEmpty()) {
                context.getString(event.messageRes)
            } else {
                context.getString(event.messageRes, *event.args.toTypedArray())
            }
            toastState.show(text)
        }
    }

    // 官方客户端行为：点击歌曲起播后自动跳转全屏播放页。
    // 收口在 PlaybackCommands.play（歌单/专辑/歌手/搜索/推荐等一切点歌路径），
    // 此处把该回调桥到覆盖层 NavController；已在播放页则不重复入栈。
    val openNowPlaying: () -> Unit = {
        if (overlayNav.currentDestination?.route != Destinations.NOW_PLAYING) {
            overlayNav.navigate(Destinations.NOW_PLAYING)
        }
    }
    LaunchedEffect(graph.playbackCommands, overlayNav) {
        graph.playbackCommands.onPlayStarted = openNowPlaying
    }
    DisposableEffect(graph.playbackCommands) {
        onDispose { graph.playbackCommands.onPlayStarted = null }
    }

    // LocalOpenNowPlaying 必须在本作用域提供（overlayNav 在此创建）；
    // 迷你播放条等入口经它打开全屏播放页，与起播钩子同一导航函数。
    CompositionLocalProvider(LocalOpenNowPlaying provides openNowPlaying) {
        Box(modifier = Modifier.fillMaxSize()) {
            TabsScreen(
                selectedTab = selectedTab,
                onSelectTab = { selectedTab = it },
                overlayNav = overlayNav,
                tabs = visibleTabs,
            )

            // 覆盖层：详情 / 登录 / 全屏播放 / 设置 / P1。
            NavHost(
                navController = overlayNav,
                startDestination = Destinations.NONE,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable(Destinations.NONE) { Box(Modifier.fillMaxSize()) }
                homeGraph(overlayNav)
                exploreGraph(overlayNav)
                searchGraph(overlayNav)
                detailGraph(overlayNav)
                fmGraph(overlayNav)
                libraryGraph(overlayNav)
                settingsGraph(overlayNav)
                loginGraph(overlayNav)
                playerGraph(overlayNav)
            }

            // 进程级 Toast 中心。
            ToastCenter(state = toastState)
        }
    }
}

/**
 * 底部 Tab 容器：渲染当前 Tab 内容 + 迷你播放条 + 悬浮 GlassTabBar。
 *
 * @param tabs 由设置页「导航栏」配置的可见 Tab 列表（已按自定义顺序排列）。
 */
@Composable
private fun TabsScreen(
    selectedTab: AppTab,
    onSelectTab: (AppTab) -> Unit,
    overlayNav: NavHostController,
    tabs: List<AppTab>,
) {
    val behindLayer = rememberGraphicsLayer()
    var behindBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var screenHeightPx by remember { mutableStateOf(0) }

    // 水丝拉断水滴交互状态（用户参考 demo 移植，见 WaterDropOverlay.kt）。
    val waterDropState = rememberWaterDropState()

    // 底部系统手势栏内边距（px）。悬浮 chrome 用 navigationBarsPadding 抬到其上，
    // 因此折射源条带必须取「手势栏之上」的底部一段，且传给 GlassTabBar 的高度也要
    // 扣掉这段，否则镜片裁剪会整体错位一个手势栏高度。
    val navInsetPx = WindowInsets.navigationBars.getBottom(LocalDensity.current)

    // 折射源条带高度（px）：必须在可组合作用域内解析 Density——
    // LaunchedEffect 的协程体内不能读取 CompositionLocal。
    val stripHeightPx = with(LocalDensity.current) {
        (Layout.tabBarHeight + Layout.bottomMargin + Layout.extraPadding).roundToPx()
    }

    // 实时折射（仅 API33+）：逐帧（节流到 ~15fps）把背后内容截成底栏条带喂给镜片，
    // 使页面内容滚动时持续透过镜片产生扭曲——这正是 IT之家液态玻璃的核心特征
    // （“文字/封面透过底部导航栏的折射效果特别明显”）。读取失败时保留上一帧，
    // 绝不置空（避免闪回扁平药丸）。
    //
    // [LENS_LIVE_CAPTURE_ENABLED] 为 false 时整条回读链路关闭（滚动掉帧的根因，
    // 见常量注释）：behindBitmap 恒为 null，镜片无折射源，底栏仅剩真·磨砂。
    LaunchedEffect(behindLayer, screenHeightPx) {
        if (LENS_LIVE_CAPTURE_ENABLED && Build.VERSION.SDK_INT >= 33 && screenHeightPx > 0) {
            var lastCaptureNanos = 0L
            var logged = false
            while (true) {
                val frameNanos = withFrameNanos { it }
                if (frameNanos - lastCaptureNanos < LIVE_CAPTURE_INTERVAL_NANOS) continue
                lastCaptureNanos = frameNanos
                try {
                    val src = behindLayer.toImageBitmap().asAndroidBitmap()
                    val h = minOf(stripHeightPx, src.height)
                    // 条带顶边与 GlassTabBar 的 stripTopInScreen 对齐
                    // （= screenHeightPx - stripHeightPx），故同样扣除手势栏高度。
                    val top = maxOf(0, src.height - navInsetPx - h)
                    if (src.width > 0 && h > 0 && top + h <= src.height) {
                        behindBitmap = Bitmap
                            .createBitmap(src, 0, top, src.width, h)
                            .asImageBitmap()
                        if (!logged) {
                            Log.i(TAG_LENS, "behind capture(live): ${src.width}x$h")
                            logged = true
                        }
                    }
                } catch (e: Exception) {
                    if (!logged) {
                        Log.i(TAG_LENS, "behind capture error: ${e.message}")
                        logged = true
                    }
                }
            }
        } else {
            behindBitmap = null
        }
    }

    // 截帧修饰符仅 API33+ 挂载：API<33 设备 behindBitmap 恒为 null、不使用镜片，
    // 若仍每帧把整屏内容重录进离屏 GraphicsLayer 再合成回来，纯属浪费 GPU，
    // 构成老设备性能回归。门控后 API<33 内容正常直绘（零回归），API33+ 行为不变。
    val captureModifier = if (Build.VERSION.SDK_INT >= 33) {
        // 将内容的绘制命令重定向到 behindLayer，再把它绘制到可见画布
        // （Compose 1.7 官方捕获模式；位图仅在 API33+ 的 LaunchedEffect 中读取）。
        //
        // 必须调用 DrawScope.record(layer) 扩展（而非 GraphicsLayer.record(density,
        // layoutDirection, size, block) 原生的四参重载）：扩展内部会把本 DrawScope 的
        // canvas 临时切换为图层的录制画布，drawContent() 才会真正画进图层；四参重载的
        // 区块接收者是图层自己的 scope，外层 drawContent() 仍画到屏幕，图层恒为空
        // （磨砂磨不到内容、镜片采样到透明→渲染成黑药丸）。
        Modifier.drawWithContent {
            behindLayer.record {
                this@drawWithContent.drawContent()
            }
            drawLayer(behindLayer)
        }
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onSizeChanged { screenHeightPx = it.height },
    ) {
        // 只把「Tab 页面内容」录进折射图层：悬浮 chrome（迷你播放条 / TabBar）必须留在
        // 图层之外，否则镜片折射到的是 TabBar 自身的深色玻璃像素而不是背后内容。
        //
        // 主题背景必须画在捕获修饰符的*内层*（.then(captureModifier) 之后）：
        // Compose 绘制顺序为外→内，background 若放在捕获修饰符外层，它的绘制发生在
        // record{} 之外，永远不会进入图层。页面（如首页列表末尾 / 我的页）不绘制底色的
        // 区域在图层里是透明的；磨砂层能透出下层根背景所以看不出问题，但镜片把透明输入
        // 渲染成黑色（选中标尺变成黑药丸）。把背景并入图层后，镜片采样到的就是真实可见
        // 的背后内容（页面像素或主题底色），与磨砂层保持一致。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(captureModifier)
                .background(MaterialTheme.colorScheme.background),
        ) {
            when (selectedTab) {
                AppTab.HOME -> HomeScreen(navController = overlayNav)
                AppTab.EXPLORE -> ExploreScreen(navController = overlayNav)
                AppTab.SEARCH -> SearchScreen(navController = overlayNav)
                AppTab.FM -> FmScreen(onOpenLogin = { overlayNav.navigate(Destinations.LOGIN) })
                AppTab.LIBRARY -> LibraryScreen(
                    onNavigate = { overlayNav.navigate(it) },
                    onOpenLogin = { overlayNav.navigate(Destinations.LOGIN) },
                )
            }
        }

        // 迷你播放条（Tab 内容之上、TabBar 上方）：以 bottom padding 抬到 TabBar 上方避免重合；
        // 收集播放状态以在有曲目时自动弹出；下滑可收起，切歌时自动重新弹出。
        // 弹出/收起带丝滑滑动动画（起播从底部滑入、收起向下滑出，官方客户端同款）。
        // navigationBarsPadding：edge-to-edge（targetSdk 35）下抬到系统手势栏之上，
        // 否则底栏被手势栏压住，不悬浮、玻璃观感丢失。
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = Layout.tabBarHeight + Layout.barSpacing),
        ) {
            val controller = LocalMusicController.current
            if (controller != null) {
                val playerState by controller.state.collectAsStateWithLifecycle()
                val currentTrackId = playerState.currentTrack?.id
                var miniPlayerCollapsed by remember { mutableStateOf(false) }
                // 切换到新曲目时自动重新弹出（用户下滑收起后，换歌会再次出现）。
                LaunchedEffect(currentTrackId) { miniPlayerCollapsed = false }
                // 打开播放页统一走 AppContent 注入的带守卫入口（原 bug：直接
                // navigate 无 currentDestination 守卫，双击重复入栈播放页）。
                val openNowPlaying = LocalOpenNowPlaying.current
                AnimatedVisibility(
                    visible = currentTrackId != null && !miniPlayerCollapsed,
                    enter = slideInVertically(
                        initialOffsetY = { it },
                        animationSpec = tween(
                            durationMillis = SMOOTH_MILLIS,
                            easing = FastOutSlowInEasing,
                        ),
                    ) + fadeIn(animationSpec = tween(durationMillis = STANDARD_MILLIS)),
                    exit = slideOutVertically(
                        targetOffsetY = { it },
                        animationSpec = tween(
                            durationMillis = SMOOTH_MILLIS,
                            easing = FastOutSlowInEasing,
                        ),
                    ) + fadeOut(animationSpec = tween(durationMillis = QUICK_MILLIS)),
                ) {
                    MiniPlayerBar(
                        // 复用带 currentDestination 守卫的入口（原 bug：直接
                        // navigate 无守卫，双击重复入栈播放页）。
                        onOpenNowPlaying = { openNowPlaying?.invoke() },
                        onCollapse = { miniPlayerCollapsed = true },
                    )
                }
            }
        }

        // navigationBarsPadding：抬到系统手势栏之上，底栏完整圆角与留白可见。
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                // 水丝拉断手势挂底栏容器（Tab clickable 的父节点，父子共享指针事件，
                // 零消费零干扰）；容器根坐标原点供局部→根换算。见 WaterDropOverlay.kt。
                .onGloballyPositioned { waterDropState.barOrigin = it.positionInRoot() }
                .waterDropGesture(waterDropState),
        ) {
            // 真·磨砂背景（仅 API33+）：把整屏 behindLayer 平移到底栏位置后经 RenderEffect
            // 高斯模糊，裁成与 GlassBox 相同的圆角矩形，垫在悬浮底栏之下。背后内容实时
            // 磨砂（滚动时随之更新），无需回读位图；读取失败/首帧时 behindLayer 为空则
            // 自然透明，不影响运行。这是 IT之家毛玻璃「糊化背后内容」的真正做法。
            if (Build.VERSION.SDK_INT >= 33) {
                var frostOrigin by remember { mutableStateOf(Offset.Zero) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = Layout.bottomMargin)
                        .height(Layout.tabBarHeight)
                        .onGloballyPositioned { frostOrigin = it.positionInRoot() }
                        .clip(RoundedCornerShape(22.dp))
                        .graphicsLayer {
                            renderEffect = BlurEffect(
                                FROST_BLUR_RADIUS,
                                FROST_BLUR_RADIUS,
                                TileMode.Clamp,
                            )
                        }
                        .drawWithContent {
                            // behindLayer 为整屏内容（原点在窗口 0,0）；平移 -frostOrigin 后，
                            // 本 Box 的视口正好落在「底栏背后」的那块内容上，再被外层模糊+圆角裁剪。
                            if (frostOrigin != Offset.Zero) {
                                translate(-frostOrigin.x, -frostOrigin.y) {
                                    drawLayer(behindLayer)
                                }
                            }
                            drawContent()
                        },
                )
            }
            GlassTabBar(
                selected = selectedTab,
                onSelect = onSelectTab,
                tabs = tabs,
                behindBackground = behindBitmap,
                // 传「手势栏之上的内容高度」：GlassTabBar 以 screenHeightPx - stripHeightPx
                // 定位条带顶边，与上面按 navInsetPx 裁出的位图保持 1:1 对齐。
                screenHeightPx = (screenHeightPx - navInsetPx).coerceAtLeast(0),
            )
        }

        // 水丝拉断水滴交互层：全屏绘制 + 手势观察（零事件消费），画在 TabBar 之上，
        // 水滴拖出底栏后仍悬浮在页面内容上方。见 WaterDropOverlay.kt。
        WaterDropOverlay(
            state = waterDropState,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.matchParentSize(),
        )
    }
}
