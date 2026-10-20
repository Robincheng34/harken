// Ported from kumone by missuo (LGPL-3.0)
//
// 歌词面板（T27 `components/LyricsPanel.kt`）。
//
// 全屏播放页的「歌词模式」：顶部歌曲百科 pill + 沉浸歌词。
// 顶栏（收起 / 歌名 / 分享）与 chrome 的显隐由 `NowPlayingScreen` 统一驱动；
// 本组件只负责「百科入口 + 歌词本体」，歌词渲染见 `ImmersiveLyrics`。

package im.missuo.kumone.feature.player.components

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import im.missuo.kumone.design.theme.GlassChip
import im.missuo.kumone.design.theme.bright
import im.missuo.kumone.design.theme.glassTokens
import im.missuo.kumone.net.lyrics.ParsedLyrics

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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            GlassChip(
                onClick = onSongInfo,
                shape = RoundedCornerShape(14.dp),
                tokens = glassTokens().bright(),
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
