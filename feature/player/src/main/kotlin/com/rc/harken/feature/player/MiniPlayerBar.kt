// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 迷你播放条（T25 `MiniPlayerBar.kt`）。
//
// 1:1 复刻 iOS `IOSMiniPlayerBar`（非 inline 形态）：
//  - 总高 52dp（`Theme.Layout.miniPlayerHeight`），iOS 26 液态玻璃胶囊（半透明填充 +
//    顶部高光 + 发丝描边 + 柔和投影）；
//  - HStack 间距 4；leading 12 / trailing 6 / vertical 4
//  - 封面 32dp（`Radius.small` = 6dp 圆角 + 描边 + 投影），点击打开播放页
//  - 标题 13sp semibold（单行）+ 艺术家 10sp，文案间距 3
//  - 上一首 / 播放暂停 / 下一首 各 44×44，图标 14/15sp；FM 模式禁上一首（0.35 不透明）

package com.rc.harken.feature.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.rc.harken.design.composition.LocalMusicController
import com.rc.harken.design.theme.GlassBox
import com.rc.harken.design.theme.Layout
import com.rc.harken.design.theme.glassTokens
import com.rc.harken.net.model.Formatters

/**
 * 迷你播放条。
 *
 * @param onOpenNowPlaying 点击封面 / 标题打开全屏播放页。
 *
 * 无当前曲目时渲染为空占位（不占位：调用方按 `state.currentTrack != null` 决定是否展示）。
 */
@Composable
fun MiniPlayerBar(
    onOpenNowPlaying: () -> Unit,
    onCollapse: () -> Unit = {},
    enableCollapse: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val controller = LocalMusicController.current
        ?: error("LocalMusicController 必须在 HarkenApp 提供")
    val state by controller.state.collectAsStateWithLifecycle()
    val track = state.currentTrack ?: return
    val onToggle = { controller.togglePlay() }
    val density = LocalDensity.current
    val swipeDownCollapseThresholdPx = with(density) { 48.dp.toPx() }

    // iOS 26 液态玻璃胶囊：半透明填充 + 顶部高光 + 发丝描边 + 柔和投影。
    GlassBox(
        shape = RoundedCornerShape(50),
        tokens = glassTokens(strong = true),
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .height(Layout.miniPlayerHeight)
            .padding(horizontal = 20.dp)
            // 下滑收起：向下拖动超过阈值即收起播放条（不暂停音乐，iOS 一致的下拉收起手势）。
            // 该手势受「下滑收起」设置开关控制；关闭时整段手势不挂载，避免无谓吞掉纵向拖动。
            .pointerInput(enableCollapse) {
                if (!enableCollapse) return@pointerInput
                var accumulated = 0f
                var triggered = false
                detectVerticalDragGestures(
                    onDragStart = { accumulated = 0f; triggered = false },
                    onDragEnd = {
                        if (!triggered && accumulated >= swipeDownCollapseThresholdPx) {
                            triggered = true
                            onCollapse()
                        }
                    },
                ) { _, dragAmount ->
                    if (dragAmount > 0f) accumulated += dragAmount
                }
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 封面 + 文案（点击打开播放页）
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onOpenNowPlaying),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AsyncImage(
                    model = Formatters.resizedImageURL(track.album.picUrl, 128),
                    contentDescription = track.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(6.dp)),
                )
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = track.name,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (track.fee == 1) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "VIP",
                                fontSize = 8.sp,
                                color = com.rc.harken.design.theme.BrandRed,
                            )
                        }
                    }
                    Text(
                        text = track.artistNames,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // 上一首（FM 模式禁用，iOS `disabled(player.isFMMode)`）
            IconButton44(
                enabled = !state.isFMMode,
                onClick = { controller.previous() },
            ) {
                Icon(
                    imageVector = Icons.Filled.SkipPrevious,
                    contentDescription = "上一首",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }

            // 播放 / 暂停
            IconButton44(enabled = true, onClick = onToggle) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.isPlaying) "暂停" else "播放",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }

            // 下一首
            IconButton44(enabled = true, onClick = { controller.next() }) {
                Icon(
                    imageVector = Icons.Filled.SkipNext,
                    contentDescription = "下一首",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** iOS `.frame(width: 44, height: 44)` 的方形按钮（FM 禁用时 0.35 不透明）。 */
@Composable
private fun IconButton44(
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.alpha(if (enabled) 1f else 0.35f)) { content() }
    }
}
