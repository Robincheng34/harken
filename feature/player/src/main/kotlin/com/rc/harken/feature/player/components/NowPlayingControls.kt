// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 播放控制条（官方客户端布局）。
//
// 进度条 + 时间 + 音质标签（「无损音质·智能音效」）+ 单行主控：
// 播放模式 | 上一首 | 白色大圆播放键 | 下一首 | 播放队列。
// 五槽等宽（`weight(1f)`），窄屏不溢出；播放键为白色大圆 + 深色图标
// （iOS `playPauseButton`：58pt 白圆 + 黑图标）。
//
// 播放模式键四态轮换（官方客户端）：顺序播放 → 随机播放 → 列表循环 → 单曲循环，
// 由调用方（屏幕层）把 (shuffleEnabled, repeatMode) 组合态推进到下一态。

package com.rc.harken.feature.player.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rc.harken.playback.RepeatMode

/**
 * 播放控制条（官方客户端布局）。
 *
 * @param isPlaying 播放中。
 * @param isFMMode FM 模式（上一首位变为「不喜欢」垃圾桶）。
 * @param repeatMode 循环模式。
 * @param shuffleEnabled 随机播放。
 * @param positionMs 当前位置。
 * @param durationMs 总时长。
 * @param qualityLabel 音质标签文案（如「无损音质·智能音效」）。
 * @param onTogglePlay 播放/暂停。
 * @param onNext 下一首。
 * @param onPrevious 上一首。
 * @param onTrash FM「不喜欢」（垃圾桶）。
 * @param onSeek 拖拽定位（毫秒）。
 * @param onCyclePlayMode 轮换播放模式（顺序→随机→列表循环→单曲循环）。
 * @param onToggleQueue 打开队列面板。
 */
@Composable
fun NowPlayingControls(
    isPlaying: Boolean,
    isFMMode: Boolean,
    repeatMode: RepeatMode,
    shuffleEnabled: Boolean,
    positionMs: Long,
    durationMs: Long,
    qualityLabel: String,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onTrash: () -> Unit,
    onSeek: (Long) -> Unit,
    onCyclePlayMode: () -> Unit,
    onToggleQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // 进度条（细轨 + 白点，官方观感）
        // 原 bug：onValueChange 每帧回调 onSeek，拖动期间 seek 风暴（卡顿 +
        // 进度被引擎位置回跳）。现拖动中维护本地滑动值仅作显示，松手
        // （onValueChangeFinished）才真正 seek。
        var dragPositionMs by remember { mutableStateOf<Long?>(null) }
        val sliderMax = durationMs.coerceAtLeast(1L).toFloat()
        val effectivePositionMs = dragPositionMs ?: positionMs
        Slider(
            value = effectivePositionMs.toFloat().coerceIn(0f, sliderMax),
            onValueChange = { dragPositionMs = it.toLong() },
            onValueChangeFinished = {
                dragPositionMs?.let(onSeek)
                dragPositionMs = null
            },
            valueRange = 0f..sliderMax,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.25f),
            ),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // 拖动中显示本地滑动位置，与滑块一致。
            Text(text = formatTime(effectivePositionMs), fontSize = 10.sp, color = Color.White.copy(alpha = 0.6f))
            Text(text = formatTime(durationMs), fontSize = 10.sp, color = Color.White.copy(alpha = 0.6f))
        }

        // 音质标签（官方：进度下方左侧小字）
        Text(
            text = qualityLabel,
            fontSize = 10.sp,
            color = Color.White.copy(alpha = 0.55f),
            modifier = Modifier.padding(start = 24.dp, top = 4.dp),
        )

        Box(modifier = Modifier.size(0.dp, 16.dp))

        // 单行主控：模式 | 上一首 | 播放 | 下一首 | 队列（五槽等宽）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 播放模式（四态：顺序/随机/列表循环/单曲循环；激活纯白，顺序态半透明）
            val modeActive = shuffleEnabled || repeatMode != RepeatMode.OFF
            ControlSlot(modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onCyclePlayMode),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = when {
                            shuffleEnabled -> Icons.Filled.Shuffle
                            repeatMode == RepeatMode.ONE -> Icons.Filled.RepeatOne
                            else -> Icons.Filled.Repeat
                        },
                        contentDescription = "播放模式",
                        tint = if (modeActive) Color.White else Color.White.copy(alpha = 0.45f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // 上一首（FM 模式为「不喜欢」垃圾桶）
            ControlSlot(modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .clickable(onClick = if (isFMMode) onTrash else onPrevious),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isFMMode) Icons.Filled.Delete else Icons.Filled.SkipPrevious,
                        contentDescription = if (isFMMode) "不喜欢" else "上一首",
                        tint = Color.White,
                        modifier = Modifier.size(if (isFMMode) 22.dp else 30.dp),
                    )
                }
            }

            // 播放 / 暂停（官方白色大圆 + 深色图标）
            ControlSlot(modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .size(62.dp)
                        .shadow(elevation = 8.dp, shape = CircleShape)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable(onClick = onTogglePlay),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = Color(0xFF1A1A1A),
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            // 下一首
            ControlSlot(modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onNext),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.SkipNext,
                        contentDescription = "下一首",
                        tint = Color.White,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }

            // 播放队列入口
            ControlSlot(modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onToggleQueue),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.QueueMusic,
                        contentDescription = "播放队列",
                        tint = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/** 单行主控的等宽槽位（点击区域居中，图标本身不拉伸）。 */
@Composable
private fun ControlSlot(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) { content() }
}

/** 毫秒 → `m:ss`。 */
private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}
