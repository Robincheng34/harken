// Ported from kumone by missuo (LGPL-3.0)
//
// 玻璃质感底部 TabBar（架构文档 §3 `ui/GlassTabBar.kt` 移植 + 滑动镜片重做）。
//
// 液态玻璃：strong 玻璃药丸托底（半透明填充 + 顶部高光 + 发丝描边 + 柔和投影），
// 选中态为「滑动液态玻璃镜片」——IT之家/酷安同款：镜片在 Tab 之间连续滑动
// （临界阻尼弹簧收尾），折射源为 live GraphicsLayer，经 BlurEffect（GPU RenderEffect）
// 实时毛玻璃化、随镜片位置（lensX）逐帧平移对齐（见 LiquidGlassLens），零回读、无滞后。

package im.missuo.kumone.ui

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import im.missuo.kumone.design.theme.AppAnimations
import im.missuo.kumone.design.theme.GlassBox
import im.missuo.kumone.design.theme.LiquidGlassLens
import im.missuo.kumone.design.theme.Layout
import im.missuo.kumone.design.theme.glassTokens
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * @param tabs 展示的 Tab 列表（由设置页「导航栏」配置的可见 Tab，默认五个主 Tab）。
 * @param selected 当前选中 Tab。
 * @param onSelect Tab 切换回调。
 * @param backdrop 底栏背后内容的 live [GraphicsLayer]（按底栏矩形录制的折射源）；
 *   null 或 API<33 时不渲染滑动镜片，仅保留玻璃材质托底。
 */
@Composable
fun GlassTabBar(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
    tabs: List<AppTab> = AppTab.entries,
    backdrop: GraphicsLayer? = null,
) {
    // 底栏横拖（iOS `GlassTabBar` 的 DragGesture）：手指横拖时按落点在 Tab 行内的
    // 相对位置**实时**切换选中项（跟手），而不是松手才跳一格。
    // rememberUpdatedState：拖拽过程中 selected 会变，若把它作为 pointerInput 的 key
    // 会导致手势被重启、拖到一半断掉；用最新的状态值读取即可。
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    var rowWidthPx by remember { mutableStateOf(0) }
    // 按压态上提到底栏层：驱动滑动镜片的 edgeHighlight（按下浮现玻璃光学轮廓）。
    var pressedTab by remember { mutableStateOf<AppTab?>(null) }

    val density = LocalDensity.current
    val rowPaddingPx = with(density) { 8.dp.toPx() }
    val cellWidthPx = if (tabs.isEmpty()) 0f else rowWidthPx.toFloat() / tabs.size
    val pillIndex = tabs.indexOf(selected).coerceAtLeast(0)
    // 滑动镜片位置（底栏本地坐标，px）：随选中 Tab 弹簧滑动（酷安/IT之家同款手感，
    // 临界阻尼无可见过冲——snappy 弹簧比 bouncy 更贴近 IT之家 62 阻尼的丝滑观感）。
    val lensX by animateFloatAsState(
        targetValue = if (cellWidthPx > 0f) rowPaddingPx + pillIndex * cellWidthPx else 0f,
        animationSpec = AppAnimations.snappy(),
        label = "lensSlide",
    )
    val cellWidthDp = with(density) { cellWidthPx.toDp() }
    val lensEdgeHighlight by animateFloatAsState(
        targetValue = if (pressedTab == selected) 1f else 0f,
        animationSpec = AppAnimations.bouncy(),
        label = "lensEdge",
    )

    // strong 玻璃（浅色 White@0.82 / 深色 0x232327@0.72，MiniPlayer 同款）：
    // 底栏悬浮在内容之上，普通 0.6 填充会被明亮封面穿透、图标对比不足；
    // strong 托底既保住玻璃通透感，又让图标在任何背景上都清晰精致。
    // 注：此前「完全透明 + 依赖底下真·磨砂层」的方案在一加 7T Pro (API33) 上
    // 因 RenderEffect(BlurEffect)+drawLayer 合成错误而翻车（错误碎片压过图标），
    // 故玻璃观感改由材质自身承担，不依赖 RenderEffect。
    GlassBox(
        shape = RoundedCornerShape(22.dp),
        tokens = glassTokens(strong = true),
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = Layout.bottomMargin)
            .height(Layout.tabBarHeight),
    ) {
        // 滑动镜片层（图标之下、玻璃填充之上）：折射源是 KumoneApp 经 captureModifier
        // 实时录制的 live GraphicsLayer（零回读），镜片经 RenderEffect 在 GPU 上消费、
        // 随 lensX 连续滑动（临界阻尼弹簧收尾），与 IT之家/酷安同款液态玻璃手感。
        if (Build.VERSION.SDK_INT >= 33 && backdrop != null && cellWidthPx > 0f) {
            Box(modifier = Modifier.matchParentSize()) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(lensX.roundToInt(), 0) }
                        .width(cellWidthDp)
                        .fillMaxHeight(),
                ) {
                LiquidGlassLens(
                    backdrop = backdrop,
                    lensX = { lensX },
                    tint = MaterialTheme.colorScheme.primary,
                    edgeHighlight = lensEdgeHighlight,
                    cornerRadiusDp = 26.dp,
                    modifier = Modifier.matchParentSize(),
                )
                }
            }
        }
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
                        onPressedChange = { pressed -> pressedTab = if (pressed) tab else null },
                    )
                }
            }
        }
    }
}

/**
 * 单个 Tab 项：点击时图标弹性缩放回弹；选中态颜色平滑过渡。
 * 按压态经 [onPressedChange] 上报给底栏层驱动镜片 edgeHighlight。
 */
@Composable
private fun TabItem(
    tab: AppTab,
    isSelected: Boolean,
    onSelect: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
    onPressedChange: (Boolean) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }
    // 同一 InteractionSource 供 clickable 与按压态监听共用（避免重复创建导致状态错位）。
    val interactionSource = remember { MutableInteractionSource() }

    // 图标 / 文案颜色随选中态平滑过渡（AppAnimations.standard：250ms ease-in-out）。
    val tint by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = AppAnimations.standard(),
        label = "tabTint",
    )

    // 按压态：监听 InteractionSource 的 Press 事件并上报（滑动镜片的
    // edgeHighlight 在底栏层统一驱动：按下浮现玻璃光学轮廓，松手归零）。
    val isPressed by interactionSource.interactions
        .map { it is PressInteraction.Press }
        .collectAsState(initial = false)
    LaunchedEffect(isPressed) { onPressedChange(isPressed) }

    // 图标在上、文案在下的纵向布局（Column；曾误改为 Box 导致图标与文字重合）。
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = modifier
            .clickable(
                interactionSource = interactionSource,
                // 玻璃表面不加 Material 涟漪（矩形波纹破坏液态玻璃观感），
                // 点击反馈由图标弹跳承担。
                indication = null,
            ) {
                // 点击反馈：按下瞬间缩小，再平滑回弹（无可见过冲——全局动效统一）。
                scope.launch {
                    scale.snapTo(0.78f)
                    scale.animateTo(targetValue = 1f, animationSpec = AppAnimations.bouncy())
                }
                onSelect(tab)
            }
            .padding(vertical = 4.dp),
    ) {
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
        Text(
            text = tab.label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
    }
}
