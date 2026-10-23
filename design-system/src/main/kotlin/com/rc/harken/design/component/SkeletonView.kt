// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 骨架屏占位（iOS `SkeletonView` 1:1 移植）。
 *
 * 用主题次要表面色 + 轻微脉动（由调用方用 `shimmer` 动画包裹亦可），默认圆角 8dp。
 */
@Composable
fun SkeletonLine(
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
    )
}

/** 圆形头像/封面骨架。 */
@Composable
fun SkeletonCircle(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
    )
}

/** 封面卡骨架（方形 1:1）。 */
@Composable
fun SkeletonCover(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
    )
}

/** 多行文本骨架（列表项通用）。 */
@Composable
fun SkeletonTextBlock(lines: Int = 2) {
    androidx.compose.foundation.layout.Column(
        modifier = Modifier.padding(8.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement
            .spacedBy(8.dp),
    ) {
        repeat(lines) { index ->
            SkeletonLine(
                height = if (index == 0) 16.dp else 12.dp,
                modifier = Modifier.fillMaxWidth(if (index == 0) 0.8f else 0.6f),
            )
        }
    }
}
