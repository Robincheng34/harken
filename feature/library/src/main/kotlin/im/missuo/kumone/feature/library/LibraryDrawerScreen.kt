// Ported from kumone by missuo (LGPL-3.0)
//
// 我的页左侧抽屉（官方新版「我的」页左上汉堡键 / 「更多」入口打开）。
//
// 只放**真有去处**的项（最近播放 / 每日推荐 / 我的收藏 / 音乐云盘 / 设置 / 扫一扫），
// 官方截图里的「我的消息 / 云贝 / 装扮中心 / 创作者中心 / AI 写歌 / 定时关闭 / 商城…」
// 均无对应接口，一个都不放，避免死按钮。
//
// 动画：遮罩淡入 + 面板自左侧滑入；关闭时先滑出动画再 pop，点击项时「先 pop 再跳转」，
// 保证目标页压在抽屉之上、抽屉不会残留在返回栈。

package im.missuo.kumone.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import im.missuo.kumone.design.composition.LocalNeteaseApi
import im.missuo.kumone.design.navigation.Destinations
import im.missuo.kumone.net.model.UserProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 抽屉面板底色。 */
private val DrawerPanelColor = Color(0xFF7A1626)

/** 面板内次要文字（白 60%）。 */
private val DrawerSecondary = Color.White.copy(alpha = 0.60f)

/** VIP 标题金色。 */
private val DrawerGold = Color(0xFFF5D28A)

/**
 * 我的页左侧抽屉页。
 *
 * @param onClose 关闭抽屉（通常为 `navController.popBackStack()`）。
 * @param onNavigate 跳转覆盖层路由。
 */
@Composable
fun LibraryDrawerScreen(
    onClose: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val api = LocalNeteaseApi.current
    var profile by remember { mutableStateOf<UserProfile?>(null) }
    LaunchedEffect(Unit) {
        profile = api?.let { runCatching { it.userAccount() }.getOrNull() }
    }

    var shown by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 关闭：先播放滑出动画，再 pop；`after` 用于「关闭后跳转」，保证 pop 先于 navigate。
    fun close(after: (() -> Unit)?) {
        if (!shown) return
        shown = false
        scope.launch {
            delay(220)
            onClose()
            after?.invoke()
        }
    }

    LaunchedEffect(Unit) { shown = true }
    BackHandler { close(null) }

    val scrimAlpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(220),
        label = "drawerScrim",
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // 遮罩：整块可点关闭，无涟漪。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f * scrimAlpha))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { close(null) },
        )

        AnimatedVisibility(
            visible = shown,
            enter = slideInHorizontally(animationSpec = tween(260)) { -it },
            exit = slideOutHorizontally(animationSpec = tween(220)) { -it },
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            DrawerPanel(
                profile = profile,
                onScan = { onNavigate(Destinations.SCAN) },
                onItemClick = { route -> onNavigate(route) },
                onSettings = { onNavigate(Destinations.SETTINGS) },
            )
        }
    }
}

/** 从左侧滑入的面板本体。 */
@Composable
private fun DrawerPanel(
    profile: UserProfile?,
    onScan: () -> Unit,
    onItemClick: (String) -> Unit,
    onSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth(0.78f)
            .clip(RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp))
            .background(DrawerPanelColor)
            // 吞掉面板区域内的点击，避免穿透到遮罩把抽屉关掉。
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {}
            .navigationBarsPadding()
            // 状态栏让位：抽屉铺满到屏幕顶部（含状态栏区域），但内容不能压在时钟/信号上。
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        // 可滚动内容（顶栏 + VIP 卡 + 菜单），底部按钮固定贴底。
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            DrawerHeader(profile = profile, onScan = onScan)
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.10f))
            Spacer(Modifier.height(12.dp))

            if ((profile?.vipType ?: 0) > 0) {
                VipCard()
                Spacer(Modifier.height(12.dp))
            }

            DrawerMenuItem(Icons.Filled.History, "最近播放") {
                onItemClick(Destinations.RECENT_PLAYS)
            }
            DrawerMenuItem(Icons.Filled.CalendarMonth, "每日推荐") {
                onItemClick(Destinations.DAILY_SONGS)
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.10f))
            DrawerMenuItem(Icons.Filled.Star, "我的收藏") {
                onItemClick(Destinations.FAVORITES)
            }
            DrawerMenuItem(Icons.Filled.Cloud, "音乐云盘") {
                onItemClick(Destinations.CLOUD)
            }
        }

        Spacer(Modifier.height(12.dp))
        SettingsButton(onClick = onSettings)
    }
}

/** 顶部行：头像 + 昵称 + `>` + 扫一扫。 */
@Composable
private fun DrawerHeader(profile: UserProfile?, onScan: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.20f)),
            contentAlignment = Alignment.Center,
        ) {
            if (!profile?.avatarUrl.isNullOrBlank()) {
                AsyncImage(
                    model = profile?.avatarUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = profile?.nickname?.ifEmpty { "未登录" } ?: "未登录",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = DrawerSecondary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        IconButton(onClick = onScan) {
            Icon(
                imageVector = Icons.Filled.QrCodeScanner,
                contentDescription = "扫一扫",
                tint = Color.White,
            )
        }
    }
}

/** VIP 卡：仅展示会员状态，不放「会员中心」死按钮（无对应接口）。 */
@Composable
private fun VipCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(text = "黑胶VIP", fontSize = 13.sp, color = DrawerGold, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(text = "已开通会员特权", fontSize = 12.sp, color = DrawerSecondary)
    }
}

/** 菜单行：图标 20dp + 文字 15sp + 右侧箭头，行高 52dp。 */
@Composable
private fun DrawerMenuItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = label,
            fontSize = 15.sp,
            color = Color.White,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.40f),
        )
    }
}

/** 底部整宽「设置」按钮。 */
@Composable
private fun SettingsButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Settings,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text = "设置", fontSize = 15.sp, color = Color.White)
    }
}
