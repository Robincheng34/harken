// 水丝拉断水滴交互（WaterDrop）— 按用户参考 demo（com.example.liquiddemo）1:1 移植。
//
// 交互（demo 同款手感）：
//  - 在底部导航栏区域按下：生成一颗品牌色半透明「水滴」；
//  - 拖动：水滴跟随手指，与按压原点之间以二次贝塞尔曲线拉出一条随距离变细的
//    「水丝」（柔性弯曲，控制点向拖动反方向偏移）；
//  - 拖距超过阈值（64dp）：水丝拉断（isBroken），水滴自由；
//  - 松手：水滴以弹簧（dampingRatio=0.4, stiffness=350）回弹——未断丝回到按压
//    原点、断丝回到松手点——同时从水滴处扩散一圈涟漪；回弹结束后水滴淡出。
//
// 与 demo 的差异（均为刻意修正/适配）：
//  - demo 在拖动期间从未把 currentDropPos 动画到手指位置（疑似 bug：拖动时水滴
//    停在原地、水丝零长度）。这里拖动期用 mutableStateOf 同步写「水滴位置」，
//    水滴实时跟手，符合注释声明的「水滴渲染位置」语义；
//  - Animatable.snapTo 不允许在 awaitPointerEventScope（受限挂起作用域）内直接
//    调用，故拖动期不走 Animatable；snapTo 一律投递到外部 scope（Main 串行，
//    与手势事件保持先后顺序）；
//  - demo 的手势是全屏 Canvas 直接消费事件（会阻断列表滚动）；这里把手势挂在
//    底栏容器（Tab clickable 的父节点——父子必然共享指针事件，而全屏兄弟节点
//    会挡住 z 序更低的 Tab，实测点击失效），且全程不消费任何指针事件；
//    绘制层是独立的全屏纯绘制 Canvas（无输入处理器、不参与命中测试）。
//  - 颜色从 demo 的蓝色改为 kumone 品牌色（primary），尺寸常数按密度换算。
//
// 坐标系：手势与绘制都在根 Box 的全屏坐标系（overlay 与手势挂同一节点），
// 无需局部→根换算。松手动画必须跑在 rememberCoroutineScope 的外部作用域
// （而非手势协程）——否则 awaitEachGesture 在动画结束前不会监听下一次按下，
// 连续快速触摸会丢手势。

package im.missuo.kumone.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 水滴交互状态机 + 动画。通过 [rememberWaterDropState] 创建；
 * 手势用 [waterDropGesture] 挂载，绘制用 [WaterDropOverlay]。
 *
 * @param scope 松手回弹 / 涟漪动画的外部作用域（见文件头注释：不能跑在手势协程里）。
 */
class WaterDropState internal constructor(
    private val scope: CoroutineScope,
) {
    /** 底栏容器在根坐标系中的原点（onGloballyPositioned 写入）。
     *  手势挂在底栏容器上（局部坐标），绘制层是全屏根坐标系——用它换算。 */
    var barOrigin by mutableStateOf(Offset.Zero)

    /** 底栏容器局部坐标 → 根坐标系。 */
    internal fun toRoot(local: Offset): Offset = local + barOrigin

    // 密度换算后的交互常数（rememberWaterDropState 里按 dp 写入）。
    internal var baseRadiusPx by mutableFloatStateOf(42f)
    internal var breakThresholdPx by mutableFloatStateOf(220f)
    internal var rippleMaxRadiusPx by mutableFloatStateOf(120f)

    // 按压原点（水丝根部）、手指实时位置与拖动期水滴位置（同步写，零帧延迟跟手）。
    internal var origin by mutableStateOf(Offset.Zero)
    internal var target by mutableStateOf(Offset.Zero)
    internal var dragPos by mutableStateOf(Offset.Zero)

    // 松手回弹动画值（拖动期不读它）与水滴不透明度（回弹结束后淡出）。
    internal val releasePos = Animatable(Offset.Zero, Offset.VectorConverter)
    internal val dropAlpha = Animatable(0f)

    // 松手波纹。
    internal val rippleRadius = Animatable(0f)
    internal val rippleAlpha = Animatable(0f)

    internal var dragging by mutableStateOf(false)
    internal var broken by mutableStateOf(false)

    private var releaseJob: Job? = null

    /** 按下：水滴在按压点生成（同步置位，动画置位投递到外部 scope）。 */
    internal fun onDown(pos: Offset) {
        releaseJob?.cancel()
        releaseJob = null
        origin = pos
        target = pos
        dragPos = pos
        dragging = true
        broken = false
        scope.launch {
            dropAlpha.snapTo(1f)
            rippleRadius.snapTo(0f)
            rippleAlpha.snapTo(0f)
        }
    }

    /** 移动：水滴实时跟手（demo 漏掉了这一步，见文件头注释），超阈值标记断丝。 */
    internal fun onMove(pos: Offset) {
        target = pos
        dragPos = pos
        if (sqrt((pos.x - origin.x) * (pos.x - origin.x) + (pos.y - origin.y) * (pos.y - origin.y)) >
            breakThresholdPx
        ) {
            broken = true
        }
    }

    /** 松手：涟漪扩散 + 水滴弹簧回弹后淡出（外部作用域，不阻塞下一次手势监听）。 */
    internal fun onRelease() {
        dragging = false
        val endPos = if (broken) target else origin
        releaseJob = scope.launch {
            launch {
                rippleAlpha.animateTo(0.4f, tween(durationMillis = 120))
                rippleRadius.animateTo(rippleMaxRadiusPx, tween(durationMillis = 320))
                rippleAlpha.animateTo(0f, tween(durationMillis = 220))
                rippleRadius.snapTo(0f)
            }
            releasePos.snapTo(dragPos)
            releasePos.animateTo(
                endPos,
                spring(dampingRatio = 0.4f, stiffness = 350f),
            )
            dropAlpha.animateTo(0f, tween(durationMillis = 250))
        }
    }

    /** 绘制用的水滴当前位置：拖动期读同步的 [dragPos]，松手后读回弹动画值。 */
    internal fun currentDropPos(): Offset = if (dragging) dragPos else releasePos.value
}

/** 创建 [WaterDropState]：注入动画作用域，并把 demo 的 px 常数按屏密度换算。 */
@Composable
fun rememberWaterDropState(): WaterDropState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    return remember {
        WaterDropState(scope).apply {
            with(density) {
                // demo 原值：baseRadius=42px、break=220px、ripple=120px（3.5x 屏）。
                baseRadiusPx = 14.dp.toPx()
                breakThresholdPx = 64.dp.toPx()
                rippleMaxRadiusPx = 34.dp.toPx()
            }
        }
    }
}

/**
 * 底栏容器上的手势观察者：容器是 Tab clickable 的**父节点**，父子节点必然共享
 * 指针事件（不消费任何事件），Tab 点击与水滴手势互不干扰——这是全屏兄弟节点
 * 覆盖做不到的（Compose 命中测试会挡住 z 序更低的兄弟节点，实测 Tab 点击失效）。
 * 手势坐标是容器局部坐标，经 [WaterDropState.toRoot] 换算为根坐标驱动绘制层。
 */
fun Modifier.waterDropGesture(state: WaterDropState): Modifier = pointerInput(state) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        state.onDown(state.toRoot(down.position))
        while (true) {
            val event = awaitPointerEvent()
            val pressedChange = event.changes.firstOrNull { it.pressed }
            if (pressedChange == null) break
            state.onMove(state.toRoot(pressedChange.position))
        }
        state.onRelease()
    }
}

/**
 * 水滴绘制层：全屏 Canvas（根坐标系），画在 TabBar 之上——水滴拖出底栏后
 * 仍悬浮在页面内容上方。**纯绘制、无 pointerInput**（无输入处理器的节点不参与
 * 命中测试，不会挡住底下任何交互）。绘制内容：涟漪 → 粘连水丝 + 根部圆点 →
 * 水滴主体（拉伸形变的贝塞尔水滴轮廓）→ 白色高光。
 *
 * @param color 水滴主色（品牌色 primary）。
 */
@Composable
fun WaterDropOverlay(
    state: WaterDropState,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val drop = state.currentDropPos()
        val origin = state.origin
        val alpha = state.dropAlpha.value
        val rippleAlpha = state.rippleAlpha.value
        if (alpha <= 0.01f && rippleAlpha <= 0.01f) return@Canvas

        val dx = drop.x - origin.x
        val dy = drop.y - origin.y
        val dragDistance = sqrt(dx * dx + dy * dy)
        val baseRadius = state.baseRadiusPx
        // 拉伸形变：拖动越远，水滴拉长压扁（demo 同款系数）。
        val stretchScaleX = 1f + (dragDistance / 700f)
        val stretchScaleY = 1f - (dragDistance / 1000f).coerceAtMost(0.3f)

        // 松手扩散波纹。
        if (rippleAlpha > 0f) {
            drawCircle(
                color = color.copy(alpha = rippleAlpha),
                radius = state.rippleRadius.value,
                center = drop,
            )
        }

        // 粘连水丝：仅拖拽且未拉断时绘制，宽度随距离变细（拉断感）。
        if (state.dragging && !state.broken && dragDistance > 10f) {
            val waterLinePath = Path().apply {
                reset()
                moveTo(origin.x, origin.y)
                // 贝塞尔控制点向拖动反方向偏移，做出柔性水丝弯曲。
                val controlX = (origin.x + drop.x) / 2f
                val controlY = (origin.y + drop.y) / 2f - dragDistance * 0.15f
                quadraticBezierTo(controlX, controlY, drop.x, drop.y)
            }
            drawPath(
                path = waterLinePath,
                color = color.copy(alpha = 0.45f * alpha),
                style = Stroke(
                    width = (baseRadius * 0.4f) *
                        (1f - dragDistance / state.breakThresholdPx).coerceAtLeast(0.08f),
                ),
            )
            // 水丝根部圆点（未断丝时可见）。
            drawCircle(
                color = color.copy(alpha = 0.55f * alpha),
                radius = baseRadius * 0.22f,
                center = origin,
            )
        }

        // 水滴主体（贝塞尔水滴轮廓）。
        val waterDropPath = Path().apply {
            reset()
            moveTo(drop.x, drop.y - baseRadius * stretchScaleY)
            quadraticBezierTo(
                drop.x + baseRadius * stretchScaleX, drop.y,
                drop.x, drop.y + baseRadius * stretchScaleY,
            )
            quadraticBezierTo(
                drop.x - baseRadius * stretchScaleX, drop.y,
                drop.x, drop.y - baseRadius * stretchScaleY,
            )
            close()
        }
        drawPath(
            path = waterDropPath,
            color = color.copy(alpha = 0.78f * alpha),
        )

        // 水滴高光。
        drawCircle(
            color = Color.White.copy(alpha = 0.35f * alpha),
            radius = baseRadius * 0.32f,
            center = Offset(drop.x, drop.y - baseRadius * stretchScaleY * 0.4f),
        )
    }
}
