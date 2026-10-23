@file:Suppress("unused")

// =====================================================================================
//  液态玻璃底栏（IT之家 风格）—— 单文件、零业务依赖，可直接拷到其他 Compose 项目使用
// =====================================================================================
//
//  效果特征（三个关键点，缺一不可）：
//   1. 条身 = 真·磨砂玻璃：把「底栏背后那条画面」录进 GraphicsLayer，再做高斯模糊铺进
//      胶囊里。所以白底页面看起来是白的，深色页面就是深色，不会出现一块白板。
//   2. 选中块 = 纯透明液态玻璃：毛玻璃折射 + 放大，不带白色填充（只有一道极淡反光和发丝边）。
//   3. 液态手感：
//      · 静止时选中块正好在条内（不溢出）；
//      · 滑动时按速度实时跟手，并按速度向上/下「鼓」出条外；
//      · 松手落位 + 一次衰减振荡（水滴晃动）。
//
//  用法（两步：① 把本文件丢进任意 module；② 在页面最外层套一层）：
//
//      import liquidnav.*
//
//      val items = listOf(
//          LiquidNavItem(Icons.Filled.Home, "首页"),
//      )
//      var selected by remember { mutableIntStateOf(0) }
//      LiquidGlassNavScaffold(
//          items = items,
//          selectedIndex = selected,
//          onSelect = { selected = it },
//      ) {
//          // 你的整屏页面内容（首页/发现/我的…）
//      }
//
//  依赖：仅 AndroidX Compose（Apache-2.0）+ Kotlin 标准库，无第三方库。
//        Compose BOM 2025.01.00（Compose UI/Foundation 1.7.x、Material3 1.3.x）已验证。
//  血统：源自上游 LGPL-3.0 项目（见 NOTICE）的 GlassTabBar，按 LGPL-3.0 保留归属；
//        视觉参考为 IT之家 / 酷安（闭源 App，仅作观感参照，未拷贝其代码或资源）。
//
//  前置条件：没有。
//   · 只需要 Jetpack Compose + Material3（你这个项目本来就有）。
//   · 不需要改 Activity、不需要引入依赖、不需要 edge-to-edge —— 开了 edge-to-edge
//     会自动避让手势区；没开就自动贴住内容底边，两种都能正常显示。
//   · Android 13（API 33）以下没有实时高斯模糊，自动降级成普通半透明玻璃，不会崩。
//   · 想微调：改下面 LiquidNavTokens（尺寸/模糊/圆角）与 LiquidLens 里的
//     0.26f（滑动鼓起幅度）、0.055f/4.5f（水滴晃动幅度/频率）。
// =====================================================================================

package liquidnav

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangedIgnoreConsumed
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** 尺寸/观感参数，想微调改这里。 */
object LiquidNavTokens {
    val barHeight: Dp = 64.dp
    val barSideInset: Dp = 12.dp
    val barCornerRadius: Dp = 30.dp
    val barBottomMargin: Dp = 6.dp
    val barFrostRadius: Dp = 18.dp      // 条身模糊（越大越糊）
    val lensCornerRadius: Dp = 26.dp
    val lensInsetX: Dp = 5.dp           // 选中块左右内缩
    val lensWidthShrink: Dp = 10.dp     // 选中块比 Tab 窄这么多（= 2 × insetX）
    val lensFrostRadius: Dp = 8.dp      // 镜片比条身更清晰
    val lensMagnification: Float = 1.28f
    /** 隐藏手势提示条的机型 navigationBars inset≈0，底栏至少抬离屏幕底这么多。 */
    val minGestureClearance: Dp = 22.dp
}

/** 一个 Tab。 */
data class LiquidNavItem(
    val icon: ImageVector,
    val label: String,
)

/**
 * 液态玻璃底栏外壳：负责「录制背后内容 + 摆放底栏」。
 *
 * 直接用这个套住页面内容即可；底栏会自动叠在最上层，并把背后那条画面录成折射源。
 *
 * @param containerColor 内容层底色。必须是不透明的页面底色，否则镜片采样到透明会变黑。
 * @param content 页面内容（会被录进折射源，所以不要在里面再放悬浮底栏）。
 */
@Composable
fun LiquidGlassNavScaffold(
    items: List<LiquidNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.background,
    selectedColor: Color = MaterialTheme.colorScheme.primary,
    unselectedColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    var sizePx by remember { mutableStateOf(IntSize.Zero) }

    val navInsetPx = WindowInsets.navigationBars.getBottom(density)
    val minGesturePx = with(density) { LiquidNavTokens.minGestureClearance.toPx() }.roundToInt()
    val chromeInsetPx = maxOf(navInsetPx, minGesturePx)

    val barLeftPx = with(density) { LiquidNavTokens.barSideInset.toPx() }.roundToInt()
    val barHeightPx = with(density) { LiquidNavTokens.barHeight.toPx() }.roundToInt()
    val barBottomMarginPx = with(density) { LiquidNavTokens.barBottomMargin.toPx() }.roundToInt()
    val barWidthPx = sizePx.width - barLeftPx * 2
    val barTopPx = sizePx.height - chromeInsetPx - barBottomMarginPx - barHeightPx

    // 折射源：把「底栏背后那一小条」用 GPU 命令录进图层（零回读、零像素搬运）。
    val backdrop = rememberGraphicsLayer()
    val captureModifier = if (Build.VERSION.SDK_INT >= 33) {
        Modifier.drawWithContent {
            if (barWidthPx > 0 && barHeightPx > 0 && barTopPx > 0) {
                backdrop.record(IntSize(barWidthPx, barHeightPx)) {
                    translate(-barLeftPx.toFloat(), -barTopPx.toFloat()) {
                        this@drawWithContent.drawContent()
                    }
                }
            }
            this@drawWithContent.drawContent()
        }
    } else {
        Modifier
    }

    Box(modifier = modifier.fillMaxSize().onSizeChanged { sizePx = it }) {
        // 底色必须在捕获修饰符的内层：Compose 由外向内绘制，放在外层就录不进图层。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(captureModifier)
                .background(containerColor),
        ) {
            content()
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = with(density) { (chromeInsetPx - navInsetPx).toDp() }),
        ) {
            LiquidGlassNavBar(
                items = items,
                selectedIndex = selectedIndex,
                onSelect = onSelect,
                backdrop = backdrop,
                selectedColor = selectedColor,
                unselectedColor = unselectedColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = LiquidNavTokens.barSideInset,
                        end = LiquidNavTokens.barSideInset,
                        bottom = LiquidNavTokens.barBottomMargin,
                    )
                    .height(LiquidNavTokens.barHeight),
            )
        }
    }
}

/**
 * 液态玻璃底栏本体。
 *
 * 通常不需要直接调用（用 [LiquidGlassNavScaffold] 即可）；如果你的内容层自己管理录制，
 * 可以只把底栏摆进来。
 *
 * @param backdrop 背后内容的 live [GraphicsLayer]（[LiquidGlassNavScaffold] 自动录制）。
 */
@Composable
fun LiquidGlassNavBar(
    items: List<LiquidNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: GraphicsLayer? = null,
    selectedColor: Color = MaterialTheme.colorScheme.primary,
    unselectedColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    if (items.isEmpty()) return
    val density = LocalDensity.current
    val currentSelected by rememberUpdatedState(selectedIndex)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val scope = rememberCoroutineScope()

    var rowWidthPx by remember { mutableStateOf(0) }
    val cellWidthPx = rowWidthPx.toFloat() / items.size
    val cellWidthDp = with(density) { cellWidthPx.toDp() }

    // ---- 镜片横向位置（唯一真值）：拖拽=跟手，其余=弹簧动画 ----
    var lensX by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var initialized by remember { mutableStateOf(false) }
    val settleSpec = spring<Float>(dampingRatio = 0.8f, stiffness = 420f) // 轻微过冲，液体落位
    var settleJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun targetXOf(index: Int) = index * cellWidthPx
    fun animateLensTo(target: Float) {
        settleJob?.cancel()
        settleJob = scope.launch {
            Animatable(lensX).animateTo(target, settleSpec) { lensX = value }
        }
    }

    // ---- 速度拉伸（0..1）：静止 0，滑动峰值 1 ----
    val lensStretch = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(cellWidthPx) {
        var prevX = Float.NaN
        var prevT = 0L
        while (true) {
            val frame = withFrameNanos { it }
            if (cellWidthPx > 0f) {
                val x = lensX
                if (!prevX.isNaN() && prevT > 0L) {
                    val dt = (frame - prevT) / 1_000_000_000f
                    if (dt > 0f) {
                        val v = abs(x - prevX) / dt
                        // 参考速度 3.2 格/秒：普通一甩就拉满
                        val target = (v / (cellWidthPx * 3.2f)).coerceIn(0f, 1f)
                        val cur = lensStretch.floatValue
                        // 非对称低通：起得快(28)、回落慢(10) → 液体黏滞感
                        val k = if (target > cur) 28f else 10f
                        var next = cur + (target - cur) * (dt * k).coerceAtMost(1f)
                        if (next < 0.002f) next = 0f
                        if (next != cur) lensStretch.floatValue = next
                    }
                }
                prevX = x
                prevT = frame
            }
        }
    }

    // ---- 水滴晃动：松手触发一次 4.5Hz 衰减振荡 ----
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
                val env = 1f - t / 0.55f
                lensWobble.floatValue =
                    sin(t * 2f * Math.PI.toFloat() * 4.5f) * env * env * 0.055f
            }
        }
    }

    // 手指 x → 镜片左缘（逐像素跟手）；选格 = 手指所在格（floor，不是四舍五入）
    fun applyFinger(fx: Float) {
        if (rowWidthPx <= 0) return
        val cell = rowWidthPx.toFloat() / items.size
        lensX = (fx - cell / 2f).coerceIn(0f, rowWidthPx - cell)
        val idx = (fx / cell).toInt().coerceIn(0, items.lastIndex)
        if (idx != currentSelected) currentOnSelect(idx)
    }

    fun finalizeDrag() {
        isDragging = false
        kickWobble()
    }

    LaunchedEffect(targetXOf(selectedIndex), isDragging) {
        if (!isDragging && cellWidthPx > 0f) {
            if (!initialized) {
                lensX = targetXOf(selectedIndex)
                initialized = true
            } else {
                animateLensTo(targetXOf(selectedIndex))
            }
        }
    }

    val shape = RoundedCornerShape(LiquidNavTokens.barCornerRadius)
    val barFrostPx = with(density) { LiquidNavTokens.barFrostRadius.toPx() }
    val glassFallback = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)

    Box(
        modifier = modifier
            .shadow(
                elevation = 10.dp,
                shape = shape,
                // clip 必须显式 false：默认是 (elevation > 0)，会把要溢出条外的镜片裁掉
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.14f),
                spotColor = Color.Black.copy(alpha = 0.20f),
            ),
    ) {
        // 1) 磨砂条身：把背后条带模糊后铺进胶囊（自己裁剪，避免溢出）
        if (backdrop != null && Build.VERSION.SDK_INT >= 33) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(shape)
                    .graphicsLayer { renderEffect = BlurEffect(barFrostPx, barFrostPx, TileMode.Clamp) }
                    .drawWithContent { drawLayer(backdrop) },
            )
            // 极淡白纱：给玻璃一点乳感；不要加太多，否则深色页面会发白
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(shape)
                    .background(Color.White.copy(alpha = 0.05f)),
            )
        } else {
            // 低版本降级：普通半透明玻璃（无模糊）
            Box(Modifier.matchParentSize().clip(shape).background(glassFallback))
        }

        // 2) 选中镜片（纯透明液态玻璃）
        if (cellWidthPx > 0f) {
            val lensInsetPx = with(density) { LiquidNavTokens.lensInsetX.toPx() }
            Box(modifier = Modifier.matchParentSize()) {
                Box(
                    modifier = Modifier
                        .graphicsLayer { translationX = lensX + lensInsetPx }
                        .width(cellWidthDp - LiquidNavTokens.lensWidthShrink)
                        .fillMaxHeight()
                        .shadow(
                            elevation = 5.dp,
                            shape = RoundedCornerShape(LiquidNavTokens.lensCornerRadius),
                            clip = false,
                            ambientColor = Color.Black.copy(alpha = 0.08f),
                            spotColor = Color.Black.copy(alpha = 0.12f),
                        ),
                ) {
                    LiquidLens(
                        backdrop = backdrop,
                        lensX = { lensX },
                        stretch = { lensStretch.floatValue },
                        wobble = { lensWobble.floatValue },
                        cornerRadius = LiquidNavTokens.lensCornerRadius,
                        frostRadius = LiquidNavTokens.lensFrostRadius,
                        magnification = LiquidNavTokens.lensMagnification,
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }
        }

        // 3) Tab 行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 必须撑满高度：否则 Row 会贴在条顶，图标+文字会偏高
                .fillMaxHeight()
                .onSizeChanged { rowWidthPx = it.width }
                .pointerInput(items.size, rowWidthPx) {
                    // 零阈值拖拽：手指一接触就跟手（不用 detectHorizontalDragGestures，
                    // 它有 ~8dp 触摸死区）。IgnoreConsumed：子 clickable 会先消费 DOWN。
                    awaitPointerEventScope {
                        while (true) {
                            val downEvent = awaitPointerEvent()
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
            items.forEachIndexed { index, item ->
                key(item.label, index) {
                    NavTabItem(
                        item = item,
                        isSelected = index == selectedIndex,
                        selectedColor = selectedColor,
                        unselectedColor = unselectedColor,
                        onSelect = { onSelect(index) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** 单个 Tab：图标 + 文字，选中变色（250ms 过渡），点击图标弹一下。 */
@Composable
private fun NavTabItem(
    item: LiquidNavItem,
    isSelected: Boolean,
    selectedColor: Color,
    unselectedColor: Color,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }
    val tint by animateColorAsState(
        targetValue = if (isSelected) selectedColor else unselectedColor,
        animationSpec = tween(durationMillis = 250),
        label = "navTint",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                scope.launch {
                    scale.snapTo(0.80f)
                    scale.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = 500f))
                }
                onSelect()
            }
            .padding(vertical = 4.dp),
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = item.label,
            tint = tint,
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                },
        )
        Text(
            text = item.label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
        )
    }
}

/**
 * 镜片本体：毛玻璃折射 + 放大 + 拉伸 + 晃动。全部在 draw 期读取，零重组。
 *
 * @param stretch 0..1，速度驱动；0=静止（完全在框内），1=鼓到最大（1.26 倍高）
 * @param wobble  ±0.055 的衰减振荡，竖向加、横向减（近似体积守恒 → 像水珠）
 */
@Composable
private fun LiquidLens(
    backdrop: GraphicsLayer?,
    lensX: () -> Float,
    stretch: () -> Float,
    wobble: () -> Float,
    cornerRadius: Dp,
    frostRadius: Dp,
    magnification: Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val cornerRadiusPx = cornerRadius.value * density
    val frostPx = frostRadius.value * density

    if (Build.VERSION.SDK_INT >= 33 && backdrop != null) {
        val blur = remember(frostPx) { BlurEffect(frostPx, frostPx, TileMode.Clamp) }
        Box(modifier = modifier) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val u = stretch()
                        val w = wobble()
                        // 静止 u=0,w=0 → scale = 1（正好在框内）；滑动时竖向鼓起
                        scaleX = 1f + 0.05f * u - 0.40f * w
                        scaleY = 1f + 0.26f * u + w
                    },
            ) {
                // 折射层：采样背后条带 → 模糊 → 放大。clip 在外、renderEffect 在内。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(cornerRadius))
                        .graphicsLayer { renderEffect = blur }
                        .drawWithContent {
                            val u = stretch()
                            val sx = 1f + 0.05f * u
                            val sy = 1f + 0.26f * u
                            val m = magnification + 0.10f * u
                            val cx = lensX() + size.width / 2f
                            translate(size.width / 2f, size.height / 2f) {
                                // 只按 sqrt 预压缩：内容会跟着 blob 一起被拉长（水感）
                                scale(m / sqrt(sx), m / sqrt(sy), pivot = Offset.Zero) {
                                    translate(-cx, -size.height / 2f) { drawLayer(backdrop) }
                                }
                            }
                        },
                )
                // 玻璃叠层：极淡顶部反光 + 一道发丝边（让 blob 形状可见，不做白填充）
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            val h = size.height
                            drawRoundRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(Color.White.copy(alpha = 0.18f), Color.Transparent),
                                    startY = 0f,
                                    endY = h * 0.55f,
                                ),
                                size = size,
                                cornerRadius = CornerRadius(cornerRadiusPx),
                            )
                            val hairline = 1.dp.toPx()
                            drawRoundRect(
                                color = Color.White.copy(alpha = 0.16f),
                                topLeft = Offset(hairline / 2f, hairline / 2f),
                                size = Size(size.width - hairline, size.height - hairline),
                                cornerRadius = CornerRadius(cornerRadiusPx),
                                style = Stroke(width = hairline),
                            )
                        },
                )
            }
        }
    } else {
        // 低版本兜底：半透明圆角块
        Box(
            modifier = modifier.drawWithContent {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.16f),
                    size = size,
                    cornerRadius = CornerRadius(cornerRadiusPx),
                )
            },
        )
    }
}

