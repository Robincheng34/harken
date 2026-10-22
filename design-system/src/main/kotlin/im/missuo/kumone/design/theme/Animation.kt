// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.design.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import kotlin.math.PI

/**
 * 动效令牌（全局动效重做版）。
 *
 * 时长遵循 100/300/500 法则：≤150ms 即时反馈、250ms 状态切换、350ms 布局/页面级、
 * **出场比入场快约 25%**（exit 220ms vs enter 300ms）。
 *
 * 缓动遵循「入场减速、出场加速、状态切换先加后减」：
 * - 进场 = expo-out（cubic-bezier(0.16,1,0.3,1)），干脆自信的减速入场；
 * - 出场 = ease-in（cubic-bezier(0.7,0,0.84,0)），加速离场不拖泥带水；
 * - 切换 = ease-in-out（cubic-bezier(0.65,0,0.35,1)）。
 *
 * 弹簧全部**去掉了可见过冲/回弹**（dampingRatio ≥ 0.9）：真实物体减速停止而不是
 * 反复弹跳，过冲会把注意力吸引到动画本身而非内容。旧版 bouncy(0.6)/MediumBouncy
 * 一类「Q 弹」参数已全部退役。
 *
 * [AppAnimations.navEnter]/[AppAnimations.navExit] 等转场工厂供 NavHost /
 * AnimatedVisibility 统一取用，禁止在调用方散写 tween/spring 参数。
 */

/** 150ms：按钮/开关即时反馈。 */
const val QUICK_MILLIS: Int = 150

/** 250ms：常规过渡（面板、淡入淡出、颜色）。 */
const val STANDARD_MILLIS: Int = 250

/** 350ms：大块位移（页面转场、封面缩放）。 */
const val SMOOTH_MILLIS: Int = 350

/** 300ms：入场（进场比出场略慢，层次感）。 */
const val ENTER_MILLIS: Int = 300

/** 220ms：出场（≈入场的 75%，离场干脆）。 */
const val EXIT_MILLIS: Int = 220

/** 入场逐个延迟 40ms。 */
const val STAGGER_DELAY_MILLIS: Long = 40L

/** 入场延迟上限 400ms。 */
const val MAX_STAGGER_DELAY_MILLIS: Long = 400L

/** 进场缓动：expo-out，减速入场。 */
val MotionEnterEasing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

/** 出场缓动：ease-in，加速离场。 */
val MotionExitEasing = CubicBezierEasing(0.7f, 0f, 0.84f, 0f)

/** 状态切换缓动：ease-in-out。 */
val MotionStandardEasing = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)

object AppAnimations {

    /** 150ms 即时反馈（ease-out）。 */
    fun <T> quick(): TweenSpec<T> = tween(durationMillis = QUICK_MILLIS, easing = MotionEnterEasing)

    /** 250ms 常规状态切换（ease-in-out）。 */
    fun <T> standard(): TweenSpec<T> =
        tween(durationMillis = STANDARD_MILLIS, easing = MotionStandardEasing)

    /** 350ms 大块位移（ease-in-out）。 */
    fun <T> smooth(): TweenSpec<T> =
        tween(durationMillis = SMOOTH_MILLIS, easing = MotionStandardEasing)

    /** 300ms 入场（expo-out）。 */
    fun <T> enter(): TweenSpec<T> = tween(durationMillis = ENTER_MILLIS, easing = MotionEnterEasing)

    /** 220ms 出场（ease-in），比入场快 ~25%。 */
    fun <T> exit(): TweenSpec<T> = tween(durationMillis = EXIT_MILLIS, easing = MotionExitEasing)

    /** 弹簧：无过冲临界阻尼，response 0.35（stiffness ≈ 322）。 */
    fun <T> spring(): SpringSpec<T> =
        spring(dampingRatio = 1f, stiffness = stiffnessFor(0.35f))

    /** 弹簧：极轻微弹性（无可见过冲），response 0.4（stiffness ≈ 247）。 */
    fun <T> bouncy(): SpringSpec<T> =
        spring(dampingRatio = 0.9f, stiffness = stiffnessFor(0.4f))

    /** 弹簧：无过冲紧致，response 0.25（stiffness ≈ 632）。 */
    fun <T> snappy(): SpringSpec<T> =
        spring(dampingRatio = 1f, stiffness = stiffnessFor(0.25f))

    // ── 转场工厂：NavHost / AnimatedVisibility 统一取用 ──────────────────────

    /**
     * 覆盖层页面进场：淡入 + 自下而上的轻微位移（1/10 屏），
     * expo-out 入场，与 iOS 模态页的纵深感一致。
     */
    fun navEnter(): EnterTransition =
        fadeIn(animationSpec = enter()) +
            slideInVertically(animationSpec = enter()) { it / 10 }

    /** 覆盖层页面离场：纯淡出（ease-in，220ms），不与进场抢占注意力。 */
    fun navExit(): ExitTransition = fadeOut(animationSpec = exit())

    /** 返回（pop）进场：纯淡入。 */
    fun navPopEnter(): EnterTransition = fadeIn(animationSpec = enter())

    /** 返回（pop）离场：淡出 + 轻微下滑，与进场互为镜像。 */
    fun navPopExit(): ExitTransition =
        fadeOut(animationSpec = exit()) +
            slideOutVertically(animationSpec = exit()) { it / 10 }

    /** 第 [index] 个入场元素的延迟毫秒（上限 [MAX_STAGGER_DELAY_MILLIS]）。 */
    fun staggerDelayMillis(index: Int): Long =
        minOf(index.toLong() * STAGGER_DELAY_MILLIS, MAX_STAGGER_DELAY_MILLIS)

    private fun stiffnessFor(responseSeconds: Float): Float {
        val angular = (2 * PI / responseSeconds).toFloat()
        return angular * angular
    }
}
