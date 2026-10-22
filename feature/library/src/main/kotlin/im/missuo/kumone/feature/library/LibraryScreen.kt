// Ported from kumone by missuo (LGPL-3.0)
//
// 我的页（官方新版样式：红底花纹头部 + 居中头像/昵称/数据 + 横向快捷入口 +
// 音乐/播客/笔记页签 + 歌单列表；左上汉堡键打开左侧抽屉）。
//
// 数据全部来自真实接口（userAccount / userPlaylists / likedAlbums / playRecords），
// 缺失字段走空态或「兜底三项」，绝不写死假数据。播客 / 笔记两个页签暂无接口，
// 只展示空态。

package im.missuo.kumone.feature.library

import androidx.compose.foundation.background
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import im.missuo.kumone.design.component.SwipeToDeleteRow
import im.missuo.kumone.design.composition.LocalSettingsRepository
import im.missuo.kumone.storage.settings.AppSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import im.missuo.kumone.design.composition.LocalIsLoggedIn
import im.missuo.kumone.design.composition.LocalMusicController
import im.missuo.kumone.design.composition.LocalNeteaseApi
import im.missuo.kumone.design.composition.LocalPlaybackCommands
import im.missuo.kumone.design.navigation.Destinations
import im.missuo.kumone.design.theme.Layout
import im.missuo.kumone.feature.library.pages.playRecordKey
import im.missuo.kumone.net.model.AlbumSummary
import im.missuo.kumone.net.model.Formatters
import im.missuo.kumone.net.model.PlaylistSummary
import im.missuo.kumone.net.model.Track
import im.missuo.kumone.playback.PlayContext
import im.missuo.kumone.playback.PlaySource
import im.missuo.kumone.player.PlayerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** 我的页 Tab 起始路由（与 `:app` `Routes.LIBRARY` 的字符串契约）。 */
const val LIBRARY_ROUTE = "library"

// MARK: - 配色（官方新版「我的」页红底）

/** 整页红底渐变（从状态栏铺到底）。 */
private val PageGradient = Brush.verticalGradient(
    listOf(Color(0xFF8C1B2B), Color(0xFF6B1220)),
)

/** 官方 VIP 徽标：黑底金字（网易云会员标识）。 */
private val VipBadgeBg = Color(0xFF1F1F1F)

/** VIP 徽标上的金色文字。 */
private val VipBadgeText = Color(0xFFF5D28A)

/** 胶囊/卡片半透明白底。 */
private val CapsuleBg = Color.White.copy(alpha = 0.10f)

/**
 * 我的页。
 *
 * @param onNavigate 跳转覆盖层路由（每日推荐 / 最近播放 / 收藏 / 云盘 / 扫一扫 / 抽屉）。
 * @param onOpenLogin 未登录时点击登录。
 */
@Composable
fun LibraryScreen(
    onNavigate: (String) -> Unit,
    onOpenLogin: () -> Unit,
    onRequestSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val api = LocalNeteaseApi.current ?: error("LocalNeteaseApi 必须在 KumoneApp 提供")
    val isLoggedIn by LocalIsLoggedIn.current.collectAsStateWithLifecycle()

    val controller = LocalMusicController.current
    val playerState by (controller?.state ?: remember { MutableStateFlow(PlayerState()) })
        .collectAsStateWithLifecycle()

    val viewModel = remember(api) { LibraryViewModel(api, isLoggedIn = { isLoggedIn }) }
    // 页面销毁时取消 ViewModel 内部协程（避免收集器常驻泄漏）。
    DisposableEffect(Unit) { onDispose { viewModel.cancel() } }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(isLoggedIn) { viewModel.load() }

    // 我的页头部背景（长按头像周围空白处可更换）：读取本地设置中的自定义背景图 URI。
    val context = LocalContext.current
    val settings = LocalSettingsRepository.current
    val settingsScope = rememberCoroutineScope()
    // 删除确认开关（设置「高级」项）：开启后「近期」左滑停顿出现红条，需继续滑或点红区才删。
    val appSettings by settings?.settings?.collectAsStateWithLifecycle(initialValue = AppSettings())
        ?: remember { mutableStateOf(AppSettings()) }
    val deleteGuard = appSettings.recentDeleteGuardEnabled
    val customBg by (settings?.activeMainImagePackId ?: MutableStateFlow(null))
        .collectAsStateWithLifecycle(initialValue = null)
    var showBgDialog by remember { mutableStateOf(false) }
    val bgLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        uri?.let {
            // 持久化读取权限，避免 App 重启后图片失效（官方选背景图后长期持有）。
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: Exception) { /* 部分来源不支持持久化，忽略 */ }
            settingsScope.launch { settings?.setActiveMainImagePack(it.toString()) }
        }
    }

    val bottomClearance = if (playerState.currentTrack != null) {
        Layout.fullChromeClearance
    } else {
        Layout.tabBarClearance
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 头部背景层：自定义背景图（带暗色压暗便于白字阅读）或默认红底渐变。
        if (!customBg.isNullOrBlank()) {
            AsyncImage(
                model = customBg,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f)))
        } else {
            Box(modifier = Modifier.fillMaxSize().background(PageGradient))
        }
        Column(modifier = Modifier.fillMaxSize()) {
            LibraryTopBar(
                onOpenDrawer = { onNavigate(Destinations.LIBRARY_DRAWER) },
                onSearch = onRequestSearch,
            )
            if (!uiState.isLoggedIn) {
                LoginPrompt(onOpenLogin = onOpenLogin, modifier = Modifier.weight(1f))
            } else {
                LoggedInContent(
                    uiState = uiState,
                    bottomClearance = bottomClearance,
                    onNavigate = onNavigate,
                    onChangeBackground = { showBgDialog = true },
                    guard = deleteGuard,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (showBgDialog) {
            AlertDialog(
                onDismissRequest = { showBgDialog = false },
                title = { Text(text = "更换背景") },
                text = { Text(text = "选择一张图片作为「我的」页背景，或恢复默认红底。") },
                confirmButton = {
                    TextButton(onClick = {
                        showBgDialog = false
                        bgLauncher.launch("image/*")
                    }) { Text(text = "选择图片") }
                },
                dismissButton = {
                    if (!customBg.isNullOrBlank()) {
                        TextButton(onClick = {
                            showBgDialog = false
                            settingsScope.launch { settings?.setActiveMainImagePack(null) }
                        }) { Text(text = "恢复默认") }
                    } else {
                        TextButton(onClick = { showBgDialog = false }) { Text(text = "取消") }
                    }
                },
            )
        }
    }
}

// MARK: - 顶部操作条

/**
 * 顶部操作条：左「汉堡三条杠」打开左侧抽屉（即设置入口），右「搜索」跳到搜索 Tab。
 */
@Composable
private fun LibraryTopBar(
    onOpenDrawer: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(48.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onOpenDrawer) {
            Icon(
                imageVector = Icons.Filled.Menu,
                contentDescription = "打开侧栏",
                tint = Color.White,
                modifier = Modifier.size(26.dp),
            )
        }
        IconButton(onClick = onSearch) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = "搜索",
                tint = Color.White,
            )
        }
    }
}

// MARK: - 未登录

/** 未登录：红底白字 + 白色实心「去登录」按钮，其余不渲染。 */
@Composable
private fun LoginPrompt(onOpenLogin: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "登录后查看你的音乐资产",
            fontSize = 15.sp,
            color = Color.White.copy(alpha = 0.80f),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onOpenLogin,
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = Color(0xFF8C1B2B),
            ),
        ) {
            Text(text = "去登录", fontWeight = FontWeight.Bold)
        }
    }
}

// MARK: - 已登录内容

@Composable
private fun LoggedInContent(
    uiState: LibraryUiState,
    bottomClearance: Dp,
    onNavigate: (String) -> Unit,
    onChangeBackground: () -> Unit,
    guard: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // 顶层页签：0 音乐 / 1 播客 / 2 笔记。
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    // 音乐页签子分类：0 近期 / 1 创建 / 2 收藏 / 3 专辑。
    var musicTab by rememberSaveable { mutableStateOf(0) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ProfileSection(uiState = uiState, onChangeBackground = onChangeBackground)
        Spacer(Modifier.height(16.dp))
        QuickEntriesRow(onNavigate = onNavigate)
        Spacer(Modifier.height(18.dp))
        LibraryTabRow(selected = selectedTab, onSelect = { selectedTab = it })

        when (selectedTab) {
            0 -> {
                MusicSubTabsRow(
                    selected = musicTab,
                    createdCount = uiState.createdPlaylists.size,
                    subscribedCount = uiState.subscribedPlaylists.size,
                    albumCount = uiState.likedAlbums.size,
                    onSelect = { musicTab = it },
                    onMore = { onNavigate(Destinations.LIBRARY_DRAWER) },
                )
                when (musicTab) {
                    0 -> RecentContent(uiState = uiState, onNavigate = onNavigate, guard = guard)
                    1 -> PlaylistRows(playlists = uiState.createdPlaylists, onNavigate = onNavigate)
                    2 -> PlaylistRows(playlists = uiState.subscribedPlaylists, onNavigate = onNavigate)
                    else -> AlbumRows(albums = uiState.likedAlbums, onNavigate = onNavigate)
                }
            }
            1 -> EmptyHint(text = "暂无播客内容")
            else -> EmptyHint(text = "暂无笔记")
        }

        Spacer(Modifier.height(bottomClearance))
    }
}

// MARK: - 个人区（居中）

@Composable
private fun ProfileSection(
    uiState: LibraryUiState,
    onChangeBackground: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { onChangeBackground() })
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 头像 88dp 圆形，外圈白色 35% 描边；无头像用 Person 图标。
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.20f))
                .border(2.dp, Color.White.copy(alpha = 0.35f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (!uiState.avatarUrl.isNullOrBlank()) {
                AsyncImage(
                    model = uiState.avatarUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        // 昵称 + VIP / 等级徽标。
        Row(
            modifier = Modifier.padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = uiState.nickname.ifEmpty { "未命名" },
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp),
            )
            if (uiState.vipType > 0) {
                Spacer(Modifier.width(6.dp))
                ProfileBadge(
                    text = "VIP",
                    background = VipBadgeBg,
                    content = VipBadgeText,
                    goldBorder = true,
                )
            }
            uiState.level?.let { level ->
                Spacer(Modifier.width(6.dp))
                ProfileBadge(
                    text = "Lv.$level",
                    background = Color.White.copy(alpha = 0.15f),
                    content = Color.White,
                )
            }
        }

        if (uiState.signature.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = uiState.signature,
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.75f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 40.dp),
            )
        }

        // 数据行：优先真实数据，全部缺失时用「歌单 / 收藏 / 专辑」兜底计数。
        val stats = statItems(uiState)
        if (stats.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                stats.forEach { (value, label) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = value,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = label,
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.60f),
                        )
                    }
                }
            }
        }
    }
}

/** 昵称旁的小徽标（VIP / 等级）。 */
@Composable
private fun ProfileBadge(
    text: String,
    background: Color,
    content: Color,
    goldBorder: Boolean = false,
) {
    Text(
        text = text,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = content,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(background)
            .then(
                if (goldBorder) {
                    Modifier.border(
                        0.8.dp,
                        Color(0xFFF5D28A).copy(alpha = 0.85f),
                        RoundedCornerShape(5.dp),
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * 数据行内容：非 null 的真实字段按「关注 / 粉丝 / Lv. / 听歌」顺序；全空则用
 * 创建歌单数 / 收藏歌单数 / 收藏专辑数兜底（均为真实计数，绝不写死）。
 */
private fun statItems(uiState: LibraryUiState): List<Pair<String, String>> {
    val items = ArrayList<Pair<String, String>>(4)
    uiState.follows?.let { items.add(it.toString() to "关注") }
    uiState.followeds?.let { items.add(it.toString() to "粉丝") }
    uiState.level?.let { items.add(it.toString() to "Lv.") }
    uiState.listenSongs?.let { items.add("${it / 60} 小时" to "听歌") }
    if (items.isNotEmpty()) return items
    return listOf(
        uiState.createdPlaylists.size.toString() to "歌单",
        uiState.subscribedPlaylists.size.toString() to "收藏",
        uiState.likedAlbums.size.toString() to "专辑",
    )
}

// MARK: - 快捷入口（横向滚动胶囊）

@Composable
private fun QuickEntriesRow(onNavigate: (String) -> Unit) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        QuickCapsule(Icons.Filled.History, "最近") { onNavigate(Destinations.RECENT_PLAYS) }
        QuickCapsule(Icons.Filled.CalendarMonth, "每日推荐") { onNavigate(Destinations.DAILY_SONGS) }
        QuickCapsule(Icons.Filled.Star, "收藏") { onNavigate(Destinations.FAVORITES) }
        QuickCapsule(Icons.Filled.Cloud, "网盘") { onNavigate(Destinations.CLOUD) }
        QuickCapsule(Icons.Filled.Download, "已下载") { onNavigate(Destinations.DOWNLOADED) }
        QuickCapsule(Icons.Filled.Apps, "更多") { onNavigate(Destinations.LIBRARY_DRAWER) }
    }
}

/** 快捷入口胶囊：半透明白底 + 图标 15dp + 文字 12sp。 */
@Composable
private fun QuickCapsule(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(CapsuleBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(15.dp),
        )
        Text(text = label, fontSize = 12.sp, color = Color.White)
    }
}

// MARK: - 页签

/** 顶层页签：音乐 / 播客 / 笔记（17sp，选中白+Bold+白色下划线）。 */
@Composable
private fun LibraryTabRow(selected: Int, onSelect: (Int) -> Unit) {
    val labels = listOf("音乐", "播客", "笔记")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val active = index == selected
            Column(
                modifier = Modifier.clickable { onSelect(index) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = label,
                    fontSize = 17.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = if (active) Color.White else Color.White.copy(alpha = 0.60f),
                )
                Spacer(Modifier.height(5.dp))
                Box(
                    modifier = Modifier
                        .width(18.dp)
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(if (active) Color.White else Color.Transparent),
                )
            }
        }
    }
}

/** 音乐页签子分类：近期 / 创建 N / 收藏 N / 专辑 N，右侧「更多」打开抽屉。 */
@Composable
private fun MusicSubTabsRow(
    selected: Int,
    createdCount: Int,
    subscribedCount: Int,
    albumCount: Int,
    onSelect: (Int) -> Unit,
    onMore: () -> Unit,
) {
    val labels = listOf("近期", "创建 $createdCount", "收藏 $subscribedCount", "专辑 $albumCount")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            labels.forEachIndexed { index, label ->
                val active = index == selected
                Text(
                    text = label,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = if (active) Color.White else Color.White.copy(alpha = 0.60f),
                    maxLines = 1,
                    modifier = Modifier.clickable { onSelect(index) },
                )
            }
        }
        IconButton(onClick = onMore, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "更多",
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// MARK: - 列表内容

/**
 * 近期：我喜欢的音乐 + 最近播放曲目。点击近期曲目直接播放（不再跳转最近播放页）。
 * 单曲左滑删除，复用「最近播放」页同一套方案：组合键（曲名|歌手|时长）本地隐藏 +
 * 自编删除接口兜底；隐藏键池进程级，同一次 App 生命周期内删除不再回弹。
 */
@Composable
private fun RecentContent(uiState: LibraryUiState, onNavigate: (String) -> Unit, guard: Boolean = false) {
    val api = LocalNeteaseApi.current ?: error("LocalNeteaseApi 必须在 KumoneApp 提供")
    val commands = LocalPlaybackCommands.current
    val scope = rememberCoroutineScope()
    val liked = uiState.likedSongs
    // 本地隐藏键池：删过的近期曲目在同一次 App 生命周期内保持隐藏（同「最近播放」页）。
    val hiddenKeys by PlayRecordHideStore.hiddenKeys.collectAsStateWithLifecycle()
    val visibleTracks = remember(uiState.recentTracks, hiddenKeys) {
        uiState.recentTracks.filter { playRecordKey(it) !in hiddenKeys }
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ListRow(
            coverUrl = liked?.coverURL,
            title = "我喜欢的音乐",
            subtitle = liked?.let { "${it.trackCount}首" },
            onClick = {
                if (liked != null) {
                    onNavigate(Destinations.playlistDetail(liked.id))
                } else {
                    onNavigate(Destinations.FAVORITES)
                }
            },
        )
        visibleTracks.forEach { track ->
            // key() 防滑动状态错位复用（同 TrackListView 的 key 教训）：普通 Column 的
            // forEach 按位置复用组合状态，删除一行后下方各行会上移接管上一行的
            // 滑动偏移——若该状态恰处「已滑出」位置，就会渲染出幽灵红色删除块。
            // 按组合键 key 住每一行后，列表收缩时各行的滑动状态各归其主，互不串位。
            key(playRecordKey(track)) {
                SwipeToDeleteRow(
                    guard = guard,
                    onPlay = {
                        // 直接起播但不自动进全屏播放页：迷你播放条浮现、停留在「我的」页。
                        scope.launch {
                            commands?.playFrom(
                                tracks = visibleTracks,
                                startTrackId = track.id,
                                source = PlaySource.NONE,
                                context = PlayContext.recents,
                                openPlayer = false,
                            )
                        }
                    },
                    onDelete = {
                        PlayRecordHideStore.hide(playRecordKey(track))
                        scope.launch { runCatching { api.deletePlayRecord(track.id) } }
                    },
                    // 内容底色取「我的」页红（与整页红底无缝，不出现一条一条实心块）。
                    contentBackground = Color(0xFF7A152B),
                ) {
                    // onClick 置空：点击已由 SwipeToDeleteRow 的 detectTapGestures 处理。
                    ListRow(
                        coverUrl = track.album.picUrl,
                        title = track.name,
                        subtitle = track.artistNames,
                        onClick = {},
                    )
                }
            }
        }
    }
}

/** 歌单列表（创建 / 收藏）。 */
@Composable
private fun PlaylistRows(playlists: List<PlaylistSummary>, onNavigate: (String) -> Unit) {
    if (playlists.isEmpty()) {
        EmptyHint(text = "暂无内容")
        return
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        playlists.forEach { playlist ->
            ListRow(
                coverUrl = playlist.coverURL,
                title = playlist.name,
                subtitle = "${playlist.trackCount}首",
                onClick = { onNavigate(Destinations.playlistDetail(playlist.id)) },
            )
        }
    }
}

/** 专辑列表（收藏的专辑）。 */
@Composable
private fun AlbumRows(albums: List<AlbumSummary>, onNavigate: (String) -> Unit) {
    if (albums.isEmpty()) {
        EmptyHint(text = "暂无内容")
        return
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        albums.forEach { album ->
            ListRow(
                coverUrl = album.picUrl,
                title = album.name,
                subtitle = album.artistName,
                onClick = { onNavigate(Destinations.albumDetail(album.id)) },
            )
        }
    }
}

/** 通用列表行：封面 52dp 圆角 + 标题 + 副标题。 */
@Composable
private fun ListRow(
    coverUrl: String?,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            val model = Formatters.resizedImageURL(coverUrl, 160)
            if (model != null) {
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.50f),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title.ifEmpty { "未知" },
                fontSize = 15.sp,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 空态提示（白 55%，14sp）。 */
@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            color = Color.White.copy(alpha = 0.55f),
        )
    }
}
