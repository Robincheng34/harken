// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// iOS 26 液态玻璃（Liquid Glass）材质系统。
//
// 设计语言要点：
//  - 半透明填充（带轻微品牌色 vibrance），让背后内容透出产生「磨砂」感；
//  - 顶部高光（specular）模拟光线在玻璃表面的反射；
//  - 上亮下暗的发丝描边（hairline border）勾出玻璃边缘；
//  - 柔和投影 + 极淡品牌色辉光（vibrancy）。
//
// 说明：Compose 公共 API 无原生 backdrop-filter，本材质不直接模糊「背后内容」。
// 当浮于图片背景（如播放页封面、详情页封面）之上时，配合该背景已有的模糊即可
// 获得真正的磨砂玻璃观感；浮于列表之上时，半透明 + 高光 + 描边 + 投影已足以
// 呈现液态玻璃质感。

package com.rc.harken.design.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 液态玻璃材质 Token。
 *
 * @param fill 半透明填充色（已含 alpha）。
 * @param specular 顶部高光渐变（白 → 透明）。
 * @param borderLight 上缘高光描边色。
 * @param borderDark 下缘暗描边色（保留备用）。
 * @param shadow 投影色。
 */
data class GlassTokens(
    val fill: Color,
    val specular: Brush,
    val borderLight: Color,
    val borderDark: Color,
    val shadow: Color,
)

/**
 * 液态玻璃 Token（随深浅色自适应）。
 *
 * @param darkTheme 是否深色；默认跟随系统。
 * @param strong 是否加强不透明度（小面积控件如迷你播放条需更高不透明度保证可读性）。
 */
@Composable
fun glassTokens(
    darkTheme: Boolean = isSystemInDarkTheme(),
    strong: Boolean = false,
): GlassTokens = if (darkTheme) {
    val fillAlpha = if (strong) 0.72f else 0.5f
    GlassTokens(
        fill = Color(0xFF232327).copy(alpha = fillAlpha),
        specular = Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.38f), Color.White.copy(alpha = 0.0f)),
        ),
        borderLight = Color.White.copy(alpha = 0.38f),
        borderDark = Color.Black.copy(alpha = 0.30f),
        shadow = Color.Black.copy(alpha = 0.42f),
    )
} else {
    val fillAlpha = if (strong) 0.82f else 0.6f
    GlassTokens(
        fill = Color.White.copy(alpha = fillAlpha),
        specular = Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.6f), Color.White.copy(alpha = 0.0f)),
        ),
        borderLight = Color.White.copy(alpha = 0.9f),
        borderDark = Color.Black.copy(alpha = 0.12f),
        shadow = Color.Black.copy(alpha = 0.18f),
    )
}

/** 深色背景（播放页）上的亮色玻璃变体：白半透明 + 高光 + 亮描边。 */
fun GlassTokens.bright(): GlassTokens = copy(
    fill = Color.White.copy(alpha = 0.22f),
    specular = Brush.verticalGradient(
        listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.08f)),
    ),
    borderLight = Color.White.copy(alpha = 0.55f),
    borderDark = Color.Black.copy(alpha = 0.0f),
    shadow = Color.Black.copy(alpha = 0.30f),
)

/**
 * 液态玻璃容器：在所有内容下层渲染「填充 + 顶部高光 + 发丝描边」，并可选投影。
 *
 * 顶层 [Modifier.clip] 会把填充/高光/描边与子内容统一裁切为 [shape]，
 * 因此圆角、胶囊、圆形均无需额外处理。
 */
@Composable
fun GlassBox(
    modifier: Modifier = Modifier,
    shape: Shape = HarkenShapes.large,
    tokens: GlassTokens = glassTokens(),
    addSpecular: Boolean = true,
    addBorder: Boolean = true,
    addShadow: Boolean = true,
    /**
     * 是否把内容裁到 [shape] 内。默认 true（玻璃容器语义）。置 false 时内容可以
     * 溢出容器边界——底栏的选中镜片需要「上下超出导航栏」（IT之家 同款）就用它，
     * 此时填充/高光/描边须自行关闭，否则未裁剪的底色会跟着溢出。
     */
    clipContent: Boolean = true,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .then(
                if (addShadow) {
                    // clip = false 必须显式写：Modifier.shadow 的默认 clip 是
                    // (elevation > 0.dp)，会顺带把内容裁到 shape 内——底栏镜片要
                    // 溢出条外时就被投影悄悄裁掉了（真机踩坑：requiredHeight 也白搭）。
                    Modifier.shadow(
                        elevation = 22.dp,
                        shape = shape,
                        clip = false,
                        spotColor = tokens.shadow,
                        ambientColor = tokens.shadow,
                    )
                } else {
                    Modifier
                },
            )
            .then(if (clipContent) Modifier.clip(shape) else Modifier)
            .drawWithContent {
                // 1) 半透明填充（vibrancy 磨砂感）。
                drawRect(color = tokens.fill)
                // 2) 顶部高光（specular）：白 → 透明，模拟玻璃表面反光。
                if (addSpecular) {
                    drawRect(
                        brush = tokens.specular,
                        topLeft = Offset(0f, 0f),
                        size = Size(size.width, size.height * 0.6f),
                    )
                }
                // 3) 上缘发丝亮线：勾出玻璃上边缘（iOS 液态玻璃标志性高光）。
                // 4) 下缘暗线：勾出玻璃下边缘，增强厚度与立体感。
                // 原 bug：下缘暗线画在 drawContent() 之后，会压住贴底内容
                // （如 TabBar 文字/图标下缘出现一条暗线割裂感）。
                // 现两道发丝线都在内容之前绘制，顺序：填充→高光→上缘亮线→
                // 下缘暗线→内容，内容始终在最上层。
                if (addBorder) {
                    val hairline = 1.dp.toPx()
                    drawLine(
                        color = tokens.borderLight,
                        start = Offset(0f, hairline / 2f),
                        end = Offset(size.width, hairline / 2f),
                        strokeWidth = hairline,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = tokens.borderDark,
                        start = Offset(0f, size.height - hairline / 2f),
                        end = Offset(size.width, size.height - hairline / 2f),
                        strokeWidth = hairline,
                        cap = StrokeCap.Round,
                    )
                }
                // 5) 子内容（绘制在填充/高光/发丝线之上）。
                drawContent()
            },
        contentAlignment = contentAlignment,
        content = content,
    )
}

/**
 * 小型液态玻璃控件（图标按钮 / 关注 pill / 小药丸）。
 *
 * 默认圆形、无投影、点击无涟漪（玻璃表面涟漪观感不佳）。
 */
@Composable
fun GlassChip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = CircleShape,
    tokens: GlassTokens = glassTokens(),
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    GlassBox(
        shape = shape,
        tokens = tokens,
        addShadow = false,
        modifier = if (enabled) {
            modifier.clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
        } else {
            modifier
        },
        contentAlignment = contentAlignment,
        content = content,
    )
}

/**
 * 液态玻璃顶栏（封装 Material3 [TopAppBar]）。
 *
 * 半透明容器让背后内容透出（vibrancy），底缘加一道发丝描边。
 * 用于详情页等需要悬浮玻璃感而不便做真实背景模糊的页面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    expandedHeight: Dp = 64.dp,
    tokens: GlassTokens = glassTokens(),
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    // 状态栏留白只做一次：M3 TopAppBar 默认 windowInsets 已含 statusBars，外层
    // 若再 displayCutoutPadding 会双重叠加（真机顶部凭空多出 ~一个状态栏高度，
    // 「发现页顶部留白过大」的根因）。改为外层 statusBarsPadding + TopAppBar
    // windowInsets 置零，其余交给 TopAppBar 自身的 64dp 高度。
    Column(modifier = modifier.statusBarsPadding()) {
        TopAppBar(
            title = title,
            navigationIcon = navigationIcon,
            actions = actions,
            windowInsets = WindowInsets(0.dp),
            modifier = Modifier.height(expandedHeight),
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = tokens.fill,
                scrolledContainerColor = tokens.fill,
                titleContentColor = contentColor,
                navigationIconContentColor = contentColor,
                actionIconContentColor = contentColor,
            ),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(tokens.borderLight.copy(alpha = 0.45f)),
        )
    }
}
