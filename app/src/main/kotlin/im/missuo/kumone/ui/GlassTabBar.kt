// Ported from kumone by missuo (LGPL-3.0)
//
// 玻璃质感底部 TabBar（架构文档 §3 `ui/GlassTabBar.kt` 1:1 移植）。
//
// iOS 26 液态玻璃：悬浮圆角玻璃药丸（左右留白 + 底部留白），半透明填充 +
// 顶部高光 + 发丝描边 + 柔和投影，营造毛玻璃托底效果。选中态以品牌红高亮
// 图标与文案；点击时图标弹性缩放回弹（iOS Tab 点击动效），选中态图标背后
// 带柔和品牌色光斑（液态玻璃「镜片」观感）。

package im.missuo.kumone.ui

import android.os.Build
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import im.missuo.kumone.design.theme.LiquidGlassLens
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import im.missuo.kumone.design.theme.GlassBox
import im.missuo.kumone.design.theme.Layout
import im.missuo.kumone.design.theme.glassTokens
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntRect
import android.graphics.Bitmap
import kotlin.math.roundToInt

/**
 * @param tabs 展示的 Tab 列表（由设置页「导航栏」配置的可见 Tab，默认五个主 Tab）。
 * @param selected 当前选中 Tab。
 * @param onSelect Tab 切换回调。
 */
@Composable
fun GlassTabBar(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
    tabs: List<AppTab> = AppTab.entries,
    behindBackground: ImageBitmap? = null,
    screenHeightPx: Int = 0,
) {
    val tokens = glassTokens()
    // 底栏横拖（iOS `GlassTabBar` 的 DragGesture）：手指横拖时按落点在 Tab 行内的
    // 相对位置**实时**切换选中项（跟手），而不是松手才跳一格。
    // rememberUpdatedState：拖拽过程中 selected 会变，若把它作为 pointerInput 的 key
    // 会导致手势被重启、拖到一半断掉；用最新的状态值读取即可。
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    var rowWidthPx by remember { mutableStateOf(0) }
    // 完全透明：导航栏不带任何颜色——无填充、无高光、无描边、无投影。
    // 玻璃观感全部来自底下「真·磨砂」层（背后内容本身被糊化）与选中镜片的折射
    // 形变，而不是在背后内容上叠一层白/灰/品牌色半透明色块。此前叠色块的写法
    // 正是「颜色也不是玻璃的透明」的来源：色块越厚，越像贴纸而不是玻璃。
    GlassBox(
        shape = RoundedCornerShape(22.dp),
        tokens = tokens.copy(fill = Color.Transparent),
        addSpecular = false,
        addBorder = false,
        addShadow = false,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = Layout.bottomMargin)
            .height(Layout.tabBarHeight),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .onSizeChanged { rowWidthPx = it.width }
                .pointerInput(tabs, rowWidthPx) {
                    // 把手指 x 映射到等宽的 Tab 单元，越过边界即时切换（iOS 同款）。
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            if (rowWidthPx > 0 && tabs.isNotEmpty()) {
                                val cell = rowWidthPx.toFloat() / tabs.size
                                val index = (change.position.x / cell).toInt()
                                    .coerceIn(0, tabs.lastIndex)
                                val target = tabs[index]
                                if (target != currentSelected) currentOnSelect(target)
                            }
                        },
                    )
                },
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                // key(tab)：设置页重排 tabs 后，TabItem 内 Animatable 等 remember
                // 状态按 tab 身份而非位置匹配（原 bug：无 key 时状态按位置错位）。
                key(tab) {
                    TabItem(
                        tab = tab,
                        isSelected = tab == selected,
                        onSelect = onSelect,
                        modifier = Modifier.weight(1f),
                        behindBackground = behindBackground,
                        screenHeightPx = screenHeightPx,
                    )
                }
            }
        }
    }
}

/**
 * 单个 Tab 项：点击时图标弹性缩放回弹；选中态颜色平滑过渡并显示柔和光斑。
 */
@Composable
private fun TabItem(
    tab: AppTab,
    isSelected: Boolean,
    onSelect: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
    behindBackground: ImageBitmap? = null,
    screenHeightPx: Int = 0,
) {
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }
    // 同一 InteractionSource 供 clickable 与按压态监听共用（避免重复创建导致状态错位）。
    val interactionSource = remember { MutableInteractionSource() }

    // 镜片区域在屏幕中的绝对位置（onPlaced 写入），用于将背后位图裁剪到本 Tab 区域，
    // 使镜片局部坐标与折射源 1:1 对齐（否则只会折射到整条位图左上角）。
    var tabRect by remember { mutableStateOf<IntRect?>(null) }
    val stripHeightPx = with(LocalDensity.current) {
        (Layout.tabBarHeight + Layout.bottomMargin + Layout.extraPadding).roundToPx()
    }

    // 图标 / 文案颜色随选中态平滑过渡。
    val tint by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(durationMillis = 200),
        label = "tabTint",
    )
    // 选中态图标背后的品牌色光斑（28dp 圆形）已按用户要求移除：
    // 「点击时出现的圆环效果」取消——选中态只保留颜色过渡（图标/文案转品牌红）
    // 与点击弹跳，底栏保持完全透明不带任何颜色。

    // 按压态：监听 InteractionSource 的 Press 事件。edgeHighlight 驱动液态玻璃镜片的
    // 光学轮廓（发丝 + 高光）。静止时恒为 0f——导航栏完全透明、不带任何描边色；
    // 只在手指按下时才浮现玻璃光学轮廓，作为液态玻璃的按压反馈。
    val isPressed by interactionSource.interactions
        .map { it is PressInteraction.Press }
        .collectAsState(initial = false)
    val edgeHighlight by animateFloatAsState(
        targetValue = if (isPressed) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "tabEdgeHighlight",
    )

    // 根容器由 Column 改为 Box：背景层（镜片 / 药丸）与内容层叠放。
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clickable(
                interactionSource = interactionSource,
                // 玻璃表面不加 Material 涟漪（矩形波纹破坏液态玻璃观感），
                // 点击反馈由图标弹跳 + 选中光斑承担。
                indication = null,
            ) {
                // 点击弹跳：按下瞬间缩小，再弹性回弹到原尺寸（iOS Tab 点击动效）。
                scope.launch {
                    scale.snapTo(0.78f)
                    scale.animateTo(
                        targetValue = 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    )
                }
                onSelect(tab)
            }
            .padding(vertical = 4.dp),
    ) {
        // 背景层（最底层）：API33+ 且有背后内容位图时使用 IT之家液态玻璃镜片；
        // 否则沿用既有半透明圆角药丸，保证老设备零回归。
        Box(
            Modifier
                .matchParentSize()
                .onPlaced { coords ->
                    val p = coords.positionInRoot()
                    tabRect = IntRect(
                        p.x.roundToInt(),
                        p.y.roundToInt(),
                        (p.x + coords.size.width).roundToInt(),
                        (p.y + coords.size.height).roundToInt(),
                    )
                },
        ) {
            // 将整条背后位图裁剪到本 Tab 的镜片区域，使镜片局部坐标与折射源 1:1 对齐。
            val lensBackground = remember(behindBackground, tabRect, screenHeightPx) {
                // tabRect 为委托属性（by mutableStateOf），Kotlin 无法对其智能转换，
                // 先取本地 val 再判空。
                val rect = tabRect
                if (behindBackground == null || rect == null || screenHeightPx <= 0) {
                    behindBackground
                } else {
                    val src = behindBackground.asAndroidBitmap()
                    val stripTopInScreen = (screenHeightPx - stripHeightPx).coerceAtLeast(0)
                    val left = rect.left.coerceIn(0, src.width)
                    val top = (rect.top - stripTopInScreen).coerceIn(0, src.height)
                    val width = (rect.right - rect.left).coerceAtMost(src.width - left)
                    val height = (rect.bottom - rect.top).coerceAtMost(src.height - top)
                    if (width > 0 && height > 0) {
                        val cropped = Bitmap.createBitmap(src, left, top, width, height)
                        // AGSL RuntimeShader 的输入着色器无法采样 HARDWARE 位图
                        // （GPU 显存，rmContent.eval 恒返回黑色）——必须复制为软件
                        // 位图。behindLayer.toImageBitmap() 产出的正是 HARDWARE 位图，
                        // 这正是选中镜片渲染成纯黑药丸的根因。
                        val soft = cropped.copy(Bitmap.Config.ARGB_8888, false)
                        soft.asImageBitmap()
                    } else {
                        behindBackground
                    }
                }
            }
            // 选中态背景：API33+ 且有折射源位图时由 IT之家液态玻璃镜片承担（当前
            // LENS_LIVE_CAPTURE_ENABLED=false，折射源恒为 null，镜片不渲染）。
            // 原半透明药丸兜底已按用户要求移除——「导航栏完全透明不带任何颜色」，
            // 选中态仅靠图标/文案的品牌红区分。
            if (isSelected && lensBackground != null && Build.VERSION.SDK_INT >= 33) {
                LiquidGlassLens(
                    background = lensBackground,
                    tint = MaterialTheme.colorScheme.primary,
                    edgeHighlight = edgeHighlight,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }

        // 内容层（既有图标 + 光斑 + 文案，完全保留）。
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = tab.icon,
                    contentDescription = tab.label,
                    tint = tint,
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer {
                            scaleX = scale.value
                            scaleY = scale.value
                        },
                )
            }
            Text(
                text = tab.label,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
            )
        }
    }
}
