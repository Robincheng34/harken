// Ported from kumone by missuo (LGPL-3.0)
//
// 玻璃质感底部 TabBar（架构文档 §3 `ui/GlassTabBar.kt` 移植 + 滑动镜片重做）。
//
// 液态玻璃：底栏本体保持透明（不引入背景色），选中态为「滑动液态玻璃镜片」——
// IT之家/酷安同款：镜片在 Tab 之间连续跟手滑动（临界阻尼弹簧收尾），折射源为 live
// GraphicsLayer，经 BlurEffect（GPU RenderEffect）实时毛玻璃化、随镜片位置（lensX）
// 逐帧平移对齐（见 LiquidGlassLens），零回读、无滞后。

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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
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
import kotlin.math.abs
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
    // 底栏横拖（iOS `GlassTabBar` 的 DragGesture）：手指横拖时镜片**逐像素跟手**滑动
    // （1:1 跟踪手指，而非松手才跳一格），高度灵敏；越过 Tab 边界即时切换选中项
    // （内容同步跟随），松手后镜片弹簧收尾到最近一格。
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    var rowWidthPx by remember { mutableStateOf(0) }
    // 按压态上提到底栏层：驱动滑动镜片的 edgeHighlight（按下浮现玻璃光学轮廓）。
    var pressedTab by remember { mutableStateOf<AppTab?>(null) }

    val density = LocalDensity.current
    val rowPaddingPx = with(density) { 8.dp.toPx() }
    val cellWidthPx = if (tabs.isEmpty()) 0f else rowWidthPx.toFloat() / tabs.size
    val pillIndex = tabs.indexOf(selected).coerceAtLeast(0)
    // 选中 Tab 对应的镜片左缘（底栏本地坐标，px）：松手/点击后弹簧滑向此处。
    val targetX = if (cellWidthPx > 0f) rowPaddingPx + pillIndex * cellWidthPx else 0f

    // 镜片当前显示左缘（单一真值来源）：拖拽中 = 跟手位置，其余 = 弹簧动画值。
    // 直接驱动 bottomBar 的 offset 与镜片 draw（零回读），逐帧刷新无需额外状态桥接。
    var lensX by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var initialized by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 松手/点击后的弹簧收尾：临界阻尼（无过冲）、response 0.35 比 snappy(0.25) 更柔，
    // 落位更丝滑。同一时刻只允许一个落位动画在跑，避免并发 animateFloat 互相打架抖动。
    val settleSpec = AppAnimations.spring<Float>()
    var settleJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun animateLensTo(target: Float) {
        settleJob?.cancel()
        settleJob = scope.launch {
            // 从当前显示位置起动画，block 每帧写回 lensX（mutableFloatState），
            // 驱动 offset 与镜片 draw；同一时刻仅一个落位动画在跑。
            Animatable(lensX).animateTo(target, settleSpec) { lensX = value }
        }
    }
    // 初次布局直接 snap（不从左边缘滑入）；其后选中项切换（非拖拽中）才弹簧收尾。
    LaunchedEffect(targetX, isDragging) {
        if (!isDragging && cellWidthPx > 0f) {
            if (!initialized) {
                lensX = targetX
                initialized = true
            } else {
                animateLensTo(targetX)
            }
        }
    }

    // 拉伸进度（0..1）：液态玻璃 blob —— 滑动速度越快镜片被拉得越长（IT之家/酷安真机
    // 录屏校准：滑动中 blob 覆盖 1.5~2 个 Tab，停稳回缩成常规药丸），这是「液态」手感
    // 的最大来源。独立逐帧循环从 lensX 取速度（低通顺化起落），写入
    // mutableFloatState 供镜片 draw-time 读取（零重组）；动画帧流停止后循环仍在跑，
    // 保证回缩尾巴能收敛到 0，不会把拉伸「冻」在半路。
    val lensStretch = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(cellWidthPx) {
        var prevX = Float.NaN
        var prevT = 0L
        while (true) {
            val frameTime = withFrameNanos { it }
            if (cellWidthPx > 0f) {
                val x = lensX
                if (!prevX.isNaN() && prevT > 0L) {
                    val dt = (frameTime - prevT) / 1_000_000_000f
                    if (dt > 0f) {
                        // 参考速度 = 5 个 cell/秒（低于此前 7）：更跟手、blob 更易触发。
                        val v = abs(x - prevX) / dt
                        val target = (v / (cellWidthPx * 5f)).coerceIn(0f, 1f)
                        val cur = lensStretch.floatValue
                        // 低通系数 18（此前 14）：响应更快、收得更利落。
                        var next = cur + (target - cur) * (dt * 18f).coerceAtMost(1f)
                        if (next < 0.002f) next = 0f
                        if (next != cur) lensStretch.floatValue = next
                    }
                }
                prevX = x
                prevT = frameTime
            }
        }
    }
    val cellWidthDp = with(density) { cellWidthPx.toDp() }
    val lensEdgeHighlight by animateFloatAsState(
        targetValue = if (pressedTab == selected) 1f else 0f,
        animationSpec = AppAnimations.bouncy(),
        label = "lensEdge",
    )

    // 底栏本体：保持透明（不引入背景色），仅保留极淡玻璃高光/发丝描边作边界定义；
    // 选中镜片自带毛玻璃与高光，无需底栏填充兜底。透明底栏让内容自然透出。
    GlassBox(
        shape = RoundedCornerShape(22.dp),
        tokens = glassTokens(strong = false).copy(
            fill = Color.Transparent,
            shadow = Color.Transparent,
        ),
        addShadow = false,
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
                    stretch = { lensStretch.floatValue },
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
                    // 把手指 x 映射到镜片左缘（逐像素跟手），越界即时切换选中项（iOS 同款）。
                    detectHorizontalDragGestures(
                        onDragStart = { isDragging = true },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            if (rowWidthPx > 0 && tabs.isNotEmpty()) {
                                val cell = rowWidthPx.toFloat() / tabs.size
                                val left = (change.position.x - cell / 2f)
                                    .coerceIn(0f, rowWidthPx - cell)
                                lensX = left
                                val index = ((left - rowPaddingPx) / cell + 0.5f)
                                    .roundToInt()
                                    .coerceIn(0, tabs.lastIndex)
                                val target = tabs[index]
                                if (target != currentSelected) currentOnSelect(target)
                            }
                        },
                        onDragEnd = {
                            if (rowWidthPx > 0 && tabs.isNotEmpty()) {
                                val cell = rowWidthPx.toFloat() / tabs.size
                                val index = ((lensX - rowPaddingPx) / cell + 0.5f)
                                    .roundToInt()
                                    .coerceIn(0, tabs.lastIndex)
                                if (tabs[index] != currentSelected) currentOnSelect(tabs[index])
                            }
                            isDragging = false
                        },
                        onDragCancel = { isDragging = false },
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
    val scale = remember { androidx.compose.animation.core.Animatable(1f) }
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
