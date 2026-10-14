// Ported from kumone by missuo (LGPL-3.0)
//
// 首页功能卡横滑行（T18 `components/FunctionCards.kt`，iOS `HomeView` `FeatureCard` 1:1 移植）。
//
// 登录态三张大卡（230×132，圆角 12dp）：
//  - 每日推荐：以当日推荐首曲封面为背景 + 日期角标；
//  - 私人漫游：蓝紫渐变（iOS `[0.16,0.20,0.42]→[0.36,0.24,0.62]`）；
//  - 心动模式：红粉渐变（iOS `[0.85,0.19,0.41]→[0.98,0.42,0.34]`）。
// 无封面时回落品牌渐变 + 左上径向白光。P0 阶段未就绪入口（每日推荐/心动模式）由
// 调用方传「敬请期待」回调，P1 批（T30）统一开放。

package im.missuo.kumone.feature.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import im.missuo.kumone.design.theme.Layout
import im.missuo.kumone.feature.home.R
import im.missuo.kumone.net.model.Formatters
import java.util.Calendar

/** 功能卡固定尺寸（iOS `FeatureCard` 230×132）。 */
private val FeatureCardWidth = 230.dp
private val FeatureCardHeight = 132.dp

/**
 * 功能卡横滑行（仅登录态展示，由调用方控制可见性）。
 *
 * @param onDaily 每日推荐入口。
 * @param onFM 私人漫游（FM 模式）。
 * @param onHeartbeat 心动模式入口。
 * @param dailyCover 每日推荐背景封面 URL（可选；为空时回落品牌渐变）。
 */
@Composable
fun FunctionCards(
    onDaily: () -> Unit,
    onFM: () -> Unit,
    onHeartbeat: () -> Unit,
    dailyCover: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // iOS 边缘留白：max(0, contentInset - spacing)。
        Spacer(Modifier.width(Layout.contentInset - 16.dp))
        FeatureCard(
            title = stringResource(R.string.home_feature_daily),
            subtitle = stringResource(R.string.home_feature_daily_subtitle),
            icon = Icons.Filled.CalendarToday,
            coverURL = Formatters.resizedImageURL(dailyCover, 512),
            showsDate = true,
            onClick = onDaily,
        )
        FeatureCard(
            title = stringResource(R.string.home_feature_fm),
            subtitle = stringResource(R.string.home_feature_fm_subtitle),
            icon = Icons.Filled.Radio,
            gradient = listOf(
                Color(red = 0.16f, green = 0.20f, blue = 0.42f),
                Color(red = 0.36f, green = 0.24f, blue = 0.62f),
            ),
            onClick = onFM,
        )
        FeatureCard(
            title = stringResource(R.string.home_feature_heartbeat),
            subtitle = stringResource(R.string.home_feature_heartbeat_subtitle),
            icon = Icons.Filled.Favorite,
            gradient = listOf(
                Color(red = 0.85f, green = 0.19f, blue = 0.41f),
                Color(red = 0.98f, green = 0.42f, blue = 0.34f),
            ),
            onClick = onHeartbeat,
        )
        Spacer(Modifier.width(Layout.contentInset - 16.dp))
    }
}

/**
 * 单张功能卡（iOS `FeatureCard` 1:1 移植）。
 *
 * @param coverURL 背景封面；非空时以封面 + 黑色渐变为底。
 * @param gradient 无封面时的品牌渐变（默认红系）。
 * @param showsDate 是否在图标上叠加当日日期数字。
 */
@Composable
private fun FeatureCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    coverURL: String? = null,
    gradient: List<Color> = listOf(
        Color(red = 0.75f, green = 0.16f, blue = 0.22f),
        Color(red = 0.95f, green = 0.35f, blue = 0.28f),
    ),
    showsDate: Boolean = false,
) {
    val shape: Shape = RoundedCornerShape(12.dp) // iOS: Theme.Radius.large
    val density = LocalDensity.current
    val radialRadius = with(density) { 220.dp.toPx() }
    Box(
        modifier = Modifier
            .width(FeatureCardWidth)
            .height(FeatureCardHeight)
            .clip(shape)
            .clickable(onClick = onClick),
    ) {
        if (coverURL != null) {
            AsyncImage(
                model = coverURL,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
            // iOS: LinearGradient([.black 0.1, .black 0.68], top → bottom)
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.1f),
                                Color.Black.copy(alpha = 0.68f),
                            ),
                        ),
                    ),
            )
        } else {
            // iOS: LinearGradient(topLeading → bottomTrailing) + 左上径向白光 0.18。
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Brush.linearGradient(gradient)),
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color.White.copy(alpha = 0.18f), Color.Transparent),
                            center = Offset.Zero,
                            radius = radialRadius,
                        ),
                    ),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp), // iOS: system(size: 22, weight: .medium)
                )
                if (showsDate) {
                    val day = remember {
                        Calendar.getInstance().get(Calendar.DAY_OF_MONTH).toString()
                    }
                    Text(
                        text = day,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp, // iOS: HomeView.swift L420（10pt bold 日期角标）
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
            Text(
                text = title,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp, // iOS: HomeView.swift L427（15pt bold）
                maxLines = 1,
            )
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // iOS: strokeBorder(.white.opacity(0.12), lineWidth: 0.5)
        Box(
            modifier = Modifier
                .matchParentSize()
                .border(0.5.dp, Color.White.copy(alpha = 0.12f), shape),
        )
    }
}
