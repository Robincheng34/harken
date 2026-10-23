// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 漫游控制条（T23 `components/FmControls.kt`）。
//
// 1:1 复刻 iOS `FMView.controls`：左「不喜欢」48dp 圆形 / 中播放键 64dp 品牌渐变
// 圆形 / 右「下一首」48dp 圆形 / 末「收藏」48dp 圆形；间距 26dp。

package com.rc.harken.feature.fm.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import com.rc.harken.design.theme.BrandGradient

/**
 * 漫游控制条。
 *
 * @param isPlaying 当前是否播放中。
 * @param isLiked 当前曲是否已收藏（决定心形实心/空心）。
 * @param onTrash 不喜欢（丢弃当前曲）。
 * @param onTogglePlay 播放 / 暂停。
 * @param onNext 下一首。
 * @param onToggleLike 收藏 / 取消收藏。
 */
@Composable
fun FmControls(
    isPlaying: Boolean,
    isLiked: Boolean,
    onTrash: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onToggleLike: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(26.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 不喜欢（iOS trash，48dp 圆形浅底）
        CircleButton(onClick = onTrash) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "不喜欢",
                tint = tint,
                modifier = Modifier.size(17.dp),
            )
        }

        // 播放 / 暂停（iOS 64dp 品牌渐变圆形）
        Box(
            modifier = Modifier
                .size(64.dp)
                .shadow(12.dp, CircleShape, ambientColor = MaterialTheme.colorScheme.primary)
                .clip(CircleShape)
                .background(BrandGradient)
                .clickable(onClick = onTogglePlay),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "暂停" else "播放",
                tint = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.size(28.dp),
            )
        }

        // 下一首（iOS forward.fill，48dp）
        CircleButton(onClick = onNext) {
            Icon(
                imageVector = Icons.Filled.SkipNext,
                contentDescription = "下一首",
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }

        // 收藏（iOS LikeButton 48dp）
        CircleButton(onClick = onToggleLike) {
            Icon(
                imageVector = if (isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = "收藏",
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** iOS `.background(.primary.opacity(0.06), in: Circle)` 的 48dp 圆形浅底按钮。 */
@Composable
private fun CircleButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
