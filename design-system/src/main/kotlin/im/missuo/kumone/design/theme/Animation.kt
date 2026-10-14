// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.design.theme

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import kotlin.math.PI

/**
 * 动效时长 Token（iOS `AppAnimation` 1:1 移植）。
 *
 * 顶层常量供组件直接引用；[AppAnimations] 提供对应的 AnimationSpec 工厂。
 *
 * - quick 150ms easeOut / standard 250ms easeInOut / smooth 350ms easeInOut
 * - spring/bouncy/snappy：SwiftUI `response`/`dampingFraction` 换算为 Compose
 *   `stiffness = (2π / response)²`
 * - stagger：入场逐个延迟 40ms，上限 400ms
 */

/** 150ms：按钮/开关即时反馈。 */
const val QUICK_MILLIS: Int = 150

/** 250ms：常规过渡（面板、淡入淡出）。 */
const val STANDARD_MILLIS: Int = 250

/** 350ms：大块位移（页面转场、封面缩放）。 */
const val SMOOTH_MILLIS: Int = 350

/** 入场逐个延迟 40ms。 */
const val STAGGER_DELAY_MILLIS: Long = 40L

/** 入场延迟上限 400ms。 */
const val MAX_STAGGER_DELAY_MILLIS: Long = 400L

object AppAnimations {

    /** 150ms easeOut。 */
    fun <T> quick(): TweenSpec<T> = tween(durationMillis = QUICK_MILLIS, easing = EaseOut)

    /** 250ms easeInOut。 */
    fun <T> standard(): TweenSpec<T> = tween(durationMillis = STANDARD_MILLIS, easing = EaseInOut)

    /** 350ms easeInOut。 */
    fun <T> smooth(): TweenSpec<T> = tween(durationMillis = SMOOTH_MILLIS, easing = EaseInOut)

    /** 弹簧：response 0.35 / damping 0.7（stiffness ≈ 316）。 */
    fun <T> spring(): SpringSpec<T> =
        spring(dampingRatio = 0.7f, stiffness = stiffnessFor(0.35f))

    /** 弹簧：response 0.4 / damping 0.6（stiffness ≈ 247），更弹。 */
    fun <T> bouncy(): SpringSpec<T> =
        spring(dampingRatio = 0.6f, stiffness = stiffnessFor(0.4f))

    /** 弹簧：response 0.25 / damping 0.8（stiffness ≈ 632），更紧。 */
    fun <T> snappy(): SpringSpec<T> =
        spring(dampingRatio = 0.8f, stiffness = stiffnessFor(0.25f))

    /** 第 [index] 个入场元素的延迟毫秒（上限 [MAX_STAGGER_DELAY_MILLIS]）。 */
    fun staggerDelayMillis(index: Int): Long =
        minOf(index.toLong() * STAGGER_DELAY_MILLIS, MAX_STAGGER_DELAY_MILLIS)

    private fun stiffnessFor(responseSeconds: Float): Float {
        val angular = (2 * PI / responseSeconds).toFloat()
        return angular * angular
    }
}
