// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 玻璃质感底部 TabBar（架构文档 §3 `ui/GlassTabBar.kt` 移植 + 滑动镜片重做）。
//
// 液态玻璃：底栏本体保持透明（不引入背景色），选中态为「滑动液态玻璃镜片」——
// IT之家/酷安同款：镜片在 Tab 之间连续跟手滑动（临界阻尼弹簧收尾），折射源为 live
// GraphicsLayer，经 BlurEffect（GPU RenderEffect）实时毛玻璃化、随镜片位置（lensX）
// 逐帧平移对齐（见 LiquidGlassLens），零回读、无滞后。

package com.rc.harken.ui

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangedIgnoreConsumed
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.rc.harken.design.theme.AppAnimations
import com.rc.harken.design.theme.GlassBox
import com.rc.harken.design.theme.Layout
import com.rc.harken.design.theme.LiquidGlassLens
import com.rc.harken.design.theme.glassTokens
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * @param tabs 展示的 Tab 列表（由设置页「导航栏」配置的可见 Tab，默认五个主 Tab）。
 * @param selected 当前选中 Tab。
 * @param onSelect Tab 切换回调。
 * @param backdrop 历史遗留参数：旧版镜片用它做折射/毛玻璃。现在选中镜片改为
 *   「条内更亮的软白块」（IT之家 观感，无折射），不再消费该图层，保留参数以
 *   兼容调用方签名。
 */
@Composable
fun GlassTabBar(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
    tabs: List<AppTab> = AppTab.entries,
    @Suppress("UNUSED_PARAMETER") backdrop: GraphicsLayer? = null,
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
    val cellWidthPx = if (tabs.isEmpty()) 0f else rowWidthPx.toFloat() / tabs.size
    val pillIndex = tabs.indexOf(selected).coerceAtLeast(0)
    // 选中 Tab 对应的镜片左缘（底栏本地坐标，px）：松手/点击后弹簧滑向此处。
    // 行无内边距，cell 与 weight(1f) 的 Tab 严格同宽，镜片与图标中心精确对齐。
    val targetX = if (cellWidthPx > 0f) pillIndex * cellWidthPx else 0f

    // 镜片当前显示左缘（单一真值来源）：拖拽中 = 跟手位置，其余 = 弹簧动画值。
    // 镜片横向位置走 graphicsLayer{ translationX } 在绘制层平移（与 blob 拉伸同机制），
    // 只重绘、不重组，亚像素丝滑；lensX 为 Float（非整数 snap），避免整像素跳变。
    var lensX by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var initialized by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 松手/点击后的弹簧收尾：临界阻尼（无过冲）、response 0.35 比 snappy(0.25) 更柔，
    // 落位更丝滑。同一时刻只允许一个落位动画在跑，避免并发动画互相打架抖动。
    // 落位弹簧：dampingRatio 0.8（轻微过冲）+ 较高刚度 —— 像液体被甩到位后轻轻
    // 忽悠一下再停住（IT之家 同款落位手感），而不是硬邦邦地刹住。
    val settleSpec = spring<Float>(dampingRatio = 0.8f, stiffness = 420f)
    var settleJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun animateLensTo(target: Float) {
        settleJob?.cancel()
        settleJob = scope.launch {
            // 从当前显示位置起动画，block 每帧写回 lensX（mutableFloatState），
            // 驱动 graphicsLayer translationX 与镜片 draw；同一时刻仅一个落位动画在跑。
            Animatable(lensX).animateTo(target, settleSpec) { lensX = value }
        }
    }
    // 水滴晃动：松开手指后给镜片一个衰减振荡（竖直鼓/横向收，像水珠回弹）。
    // 与速度拉伸叠加：滑动中拉长，落位瞬间晃两下再停。
    val lensWobble = remember { mutableFloatStateOf(0f) }
    var wobbleJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun kickWobble() {
        wobbleJob?.cancel()
        wobbleJob = scope.launch {
            val start = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val t = (now - start) / 1_000_000_000f
                if (t > 0.55f) {
                    lensWobble.floatValue = 0f
                    break
                }
                val env = (1f - t / 0.55f)
                lensWobble.floatValue =
                    kotlin.math.sin(t * 2f * Math.PI.toFloat() * 4.5f) * env * env * 0.055f
            }
        }
    }

    // 把手指 x（Row 本地坐标）映射为镜片左缘并实时跟手，同时跨格即时切换选中项。
    // 选格 = 手指所在 cell（floor(fx/cell)）：手指必须真正越过两格中线（即 cell 边界）
    // 才切换——此前用 round(fx/cell) 导致手指刚过当前格中心就误切到下一格。
    fun applyFinger(fx: Float) {
        if (rowWidthPx <= 0 || tabs.isEmpty()) return
        val cell = rowWidthPx / tabs.size.toFloat()
        val left = (fx - cell / 2f).coerceIn(0f, rowWidthPx - cell)
        lensX = left
        val idx = (fx / cell).toInt().coerceIn(0, tabs.lastIndex)
        val t = tabs[idx]
        if (t != currentSelected) currentOnSelect(t)
    }
    // 松手：镜片落位到最近一格（选中项已随手势即时更新），并触发一次水滴晃动。
    fun finalizeDrag() {
        isDragging = false
        kickWobble()
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
                        // 参考速度 = 3.2 个 cell/秒：手一快就能看到明显拉伸（液态感更足）。
                        val v = abs(x - prevX) / dt
                        val target = (v / (cellWidthPx * 3.2f)).coerceIn(0f, 1f)
                        val cur = lensStretch.floatValue
                        // 非对称低通：起速快（28）、回落慢（10）——blob 被甩出去后带一点
                        // 黏滞感再收回去，像液体而不是橡皮筋；纯对称系数会显得太「弹」。
                        val k = if (target > cur) 28f else 10f
                        var next = cur + (target - cur) * (dt * k).coerceAtMost(1f)
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

    // 底栏本体 = 真·磨砂玻璃：直接对「背后内容」做高斯模糊，而不是铺白色填充。
    // 白底页面模糊后自然显白（这就是参考图看起来是白色的原因），深色页面则显深色，
    // 不会在非白底上出现一块白色轮廓。极淡的白色 tint（0.05）只给玻璃一点乳感。
    GlassBox(
        shape = RoundedCornerShape(30.dp),
        tokens = glassTokens(strong = true).copy(
            fill = Color.Transparent,
            shadow = Color.Black.copy(alpha = 0.24f),
        ),
        addSpecular = false,
        addBorder = false,
        addShadow = true,
        // 关键：不裁剪内容 —— 选中镜片要「上下溢出」到导航栏之外（IT之家 同款），
        // 裁到 shape 内就永远看不到溢出效果。
        clipContent = false,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = Layout.bottomMargin)
            .height(Layout.tabBarHeight),
    ) {
        val frostPx = with(density) { 18.dp.toPx() }
        if (backdrop != null && Build.VERSION.SDK_INT >= 33) {
            // 模糊在 0.5x 半分辨率中间层上执行（消费端不再挂全分辨率 BlurEffect）：
            // backdrop 本身已是内容半分辨率层（HarkenApp captureModifier），1:1 录入
            // 同尺寸中间层后模糊光栅像素量降为 1/4，绘制时放大回 2x；模糊半径取
            // 全尺寸值的一半 → 视觉模糊观感不变（真机 gfxinfo 复测 GPU 7-9ms/帧的
            // 根因即全分辨率模糊，此为填充率大头）。
            val frostLayer = rememberGraphicsLayer()
            val frostHalfBlur = remember(frostPx) {
                BlurEffect(frostPx * 0.5f, frostPx * 0.5f, TileMode.Clamp)
            }
            // 磨砂层：把 HarkenApp 录制的「底栏背后条带」图层经半分辨率模糊铺满底栏。
            // 图层坐标原点就是条带左上角，与底栏本地坐标对齐，无需平移。
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(30.dp))
                    .drawWithContent {
                        val halfW = (size.width / 2f).roundToInt().coerceAtLeast(1)
                        val halfH = (size.height / 2f).roundToInt().coerceAtLeast(1)
                        frostLayer.record(IntSize(halfW, halfH)) {
                            drawLayer(backdrop)
                        }
                        frostLayer.renderEffect = frostHalfBlur
                        scale(2f, 2f, pivot = Offset.Zero) { drawLayer(frostLayer) }
                    },
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(30.dp))
                    .background(Color.White.copy(alpha = 0.05f)),
            )
        } else {
            // API<33 / 无折射源兜底：普通半透明玻璃（无模糊能力）。
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(glassTokens().fill),
            )
        }
        // 选中镜片 = 纯透明液态玻璃（毛玻璃 + 放大折射 + 滑动拉伸），不带白色填充；
        // 只有极淡的顶部反光描出玻璃存在感，随手指逐像素跟手。
        if (cellWidthPx > 0f) {
            // 竖向比导航栏高出 22%，上下各溢出一半。注意父 Box 必须用默认 TopStart
            // 对齐：用 contentAlignment=Center 的话子项先从居中位置摆放、再叠加
            // translationX=lensX，位置会整体偏移（真机踩坑）。
            // 左右各内缩 5dp：贴死条子的圆角端会连成一片，看起来不像独立的一块。
            val lensInsetPx = with(density) { 5.dp.toPx() }
            Box(modifier = Modifier.matchParentSize()) {
                Box(
                    modifier = Modifier
                        .graphicsLayer {
                            translationX = lensX + lensInsetPx
                            // 静止 = 1.0：镜片正好在框内（IT之家 只在滑动过程中溢出）。
                            // 溢出与水滴晃动全部由 LiquidGlassLens 内层按速度/晃动值驱动。
                        }
                        .width(cellWidthDp - 10.dp)
                        .fillMaxHeight()
                        // 柔和投影：白底页面上镜片与背景同色，只能靠投影看出它「探出条外」。
                        .shadow(
                            elevation = 6.dp,
                            shape = RoundedCornerShape(26.dp),
                            clip = false,
                            ambientColor = Color.Black.copy(alpha = 0.10f),
                            spotColor = Color.Black.copy(alpha = 0.14f),
                        ),
                ) {
                    LiquidGlassLens(
                        backdrop = backdrop,
                        lensX = { lensX },
                        tint = MaterialTheme.colorScheme.primary,
                        edgeHighlight = lensEdgeHighlight,
                        cornerRadiusDp = 26.dp,
                        stretch = { lensStretch.floatValue },
                        wobble = { lensWobble.floatValue },
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 关键：撑满底栏高度，否则 Row 按内容高度贴在条顶（GlassBox 内容默认
                // TopStart 对齐），图标+文字会比条子中心高出约 6dp——真机实测偏上 23px。
                .fillMaxHeight()
                .onSizeChanged { rowWidthPx = it.width }
                .pointerInput(tabs, rowWidthPx) {
                    // 零阈值（zero-slop）横拖：手指一接触立刻跟手（消除 detectHorizontalDragGestures
                    // 默认的 ~8dp 触摸死区），逐像素 1:1 贴着手指走，跨格即时切换选中项；
                    // 松手后镜片弹簧收尾到最近一格。选中项随手势即时更新，内容同步跟随。
                    // 使用公开 awaitPointerEventScope + AwaitPointerEventScope.awaitPointerEvent
                    // （Compose 1.6 内部未暴露 awaitFirstDown，故用 changedToDown 取按下事件）。
                    awaitPointerEventScope {
                        while (true) {
                            val downEvent = awaitPointerEvent()
                            // IgnoreConsumed：子 TabItem 的 clickable 在 Main pass 先于本
                            // Handler 消费 DOWN，若用 changedToDown()（检查消费位）会永远
                            // 等不到按下事件，拖拽整体失效（真机踩坑）。
                            val down = downEvent.changes
                                .firstOrNull { it.changedToDownIgnoreConsumed() } ?: continue
                            isDragging = true
                            applyFinger(down.position.x)
                            var dragging = true
                            while (dragging) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: continue
                                if (change.changedToUpIgnoreConsumed() || !change.pressed) {
                                    dragging = false
                                    break
                                }
                                if (change.positionChangedIgnoreConsumed()) {
                                    change.consume()
                                    applyFinger(change.position.x)
                                }
                            }
                            finalizeDrag()
                        }
                    }
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
    // 只有选中项带品牌色（primary 红）；未选中用中性 onSurface（近黑/近白），
    // 不用 onSurfaceVariant（暖棕灰 #6E5C5C，视觉上仍像「有颜色」，与 IT之家一致）。
    val tint by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface
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
