// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rc.harken.design.theme.BrandGradient
import com.rc.harken.design.theme.QUICK_MILLIS

/**
 * 品牌渐变主按钮（iOS `GradientButton` 1:1 移植）。
 *
 * 圆角 20dp（panel）以贴合 iOS 大圆角；点击有 150ms 即时反馈（由调用方组合）。
 */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    brush: Brush = BrandGradient,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (enabled) brush else Brush.linearGradient(listOf(androidx.compose.ui.graphics.Color.Gray, androidx.compose.ui.graphics.Color.Gray)))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(PaddingValues(horizontal = 24.dp, vertical = 12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = androidx.compose.ui.graphics.Color.White,
            fontWeight = FontWeight.Bold,
        )
    }
}
