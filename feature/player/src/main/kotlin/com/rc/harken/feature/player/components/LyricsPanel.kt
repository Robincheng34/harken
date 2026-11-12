// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 歌词面板（T27 `components/LyricsPanel.kt`）。
//
// 全屏播放页的「歌词模式」：顶部歌曲百科 pill + 沉浸歌词。
// 顶栏（收起 / 歌名 / 分享）与 chrome 的显隐由 `NowPlayingScreen` 统一驱动；
// 本组件只负责「百科入口 + 歌词本体」，歌词渲染见 `ImmersiveLyrics`。

package com.rc.harken.feature.player.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rc.harken.design.theme.AppAnimations
import com.rc.harken.design.theme.GlassChip
import com.rc.harken.design.theme.bright
import com.rc.harken.design.theme.glassTokens
import com.rc.harken.net.lyrics.ParsedLyrics
import kotlinx.coroutines.launch

/**
 * 歌词面板。
 *
 * 结构（对照官方客户端歌词页）：顶部「歌曲百科」pill + 沉浸歌词列表。
 * 沉浸模式下 chrome（社交行 / 进度 / 控制条 / 五图标行）由播放页收起，
 * 本面板随之占满整屏，仅保留百科入口与歌词。
 *
 * @param lyrics 已解析歌词（可空）。
 * @param positionSeconds 当前播放位置。
 * @param showTranslation 显示翻译。
 * @param verbatimLyrics 逐字高亮。
 * @param isScrubbing 触摸中。
 * @param onSeekTo 点击行跳转。
 * @param onScrubbingChange 触摸状态。
 * @param onSongInfo 打开歌曲百科页。
 */
@Composable
fun LyricsPanel(
    lyrics: ParsedLyrics?,
    positionSeconds: Double,
    showTranslation: Boolean,
    verbatimLyrics: Boolean,
    isScrubbing: Boolean,
    onSeekTo: (Double) -> Unit,
    onScrubbingChange: (Boolean) -> Unit,
    onSongInfo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        // 顶部「歌曲百科」pill（官方歌词页入口，居中）。
        // 液态玻璃纯透明（fill=Transparent，只留高光/描边），点击带液体弹回弹：
        // 按下瞬间缩到 0.84，再以弹簧（bouncy）回弹到 1——水滴被按瘪后回弹的手感。
        val liquidScope = rememberCoroutineScope()
        val liquidScale = remember { Animatable(1f) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            GlassChip(
                onClick = {
                    liquidScope.launch {
                        liquidScale.snapTo(0.84f)
                        liquidScale.animateTo(1f, AppAnimations.bouncy())
                    }
                    onSongInfo()
                },
                shape = MaterialTheme.shapes.large,
                // 纯透明液态玻璃：无填充、无投影，只留极淡顶部高光 + 发丝描边界定
                // 玻璃轮廓（bright() 默认 0.55 alpha 白色泛光会把整颗药丸糊成灰白色，
                // 不透明——必须同时压掉 specular）。
                tokens = glassTokens().bright().copy(
                    fill = Color.Transparent,
                    specular = Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.10f), Color.Transparent),
                    ),
                    borderLight = Color.White.copy(alpha = 0.32f),
                    shadow = Color.Transparent,
                ),
                modifier = Modifier.graphicsLayer {
                    scaleX = liquidScale.value
                    scaleY = liquidScale.value
                },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "歌曲百科",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        ImmersiveLyrics(
            lyrics = lyrics,
            positionSeconds = positionSeconds,
            showTranslation = showTranslation,
            verbatimLyrics = verbatimLyrics,
            isScrubbing = isScrubbing,
            onSeekTo = onSeekTo,
            onScrubbingChange = onScrubbingChange,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
