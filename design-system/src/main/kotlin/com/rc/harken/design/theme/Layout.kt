// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken.design.theme

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 布局 Token（iOS `Theme.Layout` 1:1 移植，仅保留 Android 侧使用的部分）。
 *
 * 浮栏体系：底部 GlassTabBar 64dp + 迷你播放条 52dp；内容净空按
 * [tabBarClearance] / [fullChromeClearance] 计算。
 */
object Layout {

    /** 底部手势区保底净空：隐藏手势提示条的机型 navigationBars inset≈0，但
     * 「上滑回桌面」手势热区仍在，底栏至少要抬离屏幕底边这个高度。 */
    val minGestureClearance: Dp = 22.dp
    /** 页面左右内容缩进。 */
    val contentInset: Dp = 24.dp

    /**
     * 分区之间的统一间距基准。
     *
     * 首页 `LazyColumn` 的 `verticalArrangement` 与各 shelf 内部垂直留白均以此为准：
     * 各 shelf 内部不再自带上下 padding，相邻分区的间隔完全由列表统一提供，
     * 从而保证任意两个相邻分区的视觉间隔完全一致。
     */
    val sectionSpacing: Dp = 16.dp

    /** 封面卡基准尺寸（横滑 shelf 自适应前的基准）。 */
    val cardSize: Dp = 160.dp

    /** 底部 TabBar 总高（56dp 内容 + 2×4dp inset）。 */
    val tabBarHeight: Dp = 64.dp

    /** 迷你播放条总高（44dp 按钮 + 2×4dp 内边距）。 */
    val miniPlayerHeight: Dp = 52.dp

    /** 迷你条与 TabBar 间距。 */
    val barSpacing: Dp = 8.dp

    /** TabBar 下方留白。 */
    val bottomMargin: Dp = 6.dp

    /** 浮栏呼吸间距。 */
    val extraPadding: Dp = 12.dp

    /** 封面 shelf 行高：封面 + 两行标题 + 一行副标题。 */
    val coverShelfHeight: Dp = 226.dp

    /** 歌手 shelf 行高：圆形封面 + 一行名字。 */
    val artistShelfHeight: Dp = 196.dp

    /** 仅 TabBar 可见时的内容净空。 */
    val tabBarClearance: Dp = tabBarHeight + bottomMargin + extraPadding

    /** 迷你条 + TabBar 均可见时的内容净空。 */
    val fullChromeClearance: Dp =
        miniPlayerHeight + barSpacing + tabBarHeight + bottomMargin + extraPadding

    /**
     * 底栏底部相对屏幕底边的实际抬升（navigationBars inset 与 [minGestureClearance]
     * 取大者）。列表尾部净空 = 静态 clearance + 此值，保证最后一行不被抬升后的
     * 底栏盖住（隐藏手势条的机型 inset≈0 时取保底 22dp）。
     */
    @Composable
    fun bottomGestureInset(): Dp {
        val inset = with(LocalDensity.current) {
            WindowInsets.navigationBars.getBottom(this).toDp()
        }
        return if (inset > minGestureClearance) inset else minGestureClearance
    }

    /**
     * 横滑 shelf 的自适应卡宽（iOS `fittedShelfCardSize`）：
     * 按可用宽度与基准卡尺寸计算可见张数，再均分。
     */
    fun fittedShelfCardSize(
        availableWidth: Dp,
        leadingInset: Dp,
        spacing: Dp = 16.dp,
    ): Dp {
        val usable = availableWidth - leadingInset - spacing
        if (usable <= 0.dp) return 1.dp
        val visibleCount = maxOf(
            1,
            ceilDiv(usable + spacing, cardSize + spacing),
        )
        // Dp × Int is defined (Dp × Dp is not): total gap = spacing × gaps.
        val total = usable - spacing * (visibleCount - 1)
        return maxOf(1.dp, total / visibleCount)
    }

    /** Dp 整除向上取整（避免引入 Float 精度问题的小工具）。 */
    private fun ceilDiv(numerator: Dp, denominator: Dp): Int {
        val n = numerator.value
        val d = denominator.value
        if (d <= 0f) return 1
        return kotlin.math.ceil((n / d).toDouble()).toInt()
    }
}
