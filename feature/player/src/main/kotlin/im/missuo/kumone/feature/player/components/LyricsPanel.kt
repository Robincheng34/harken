// Ported from kumone by missuo (LGPL-3.0)
//
// 歌词面板（T27 `components/LyricsPanel.kt`）。
//
// 全屏播放页的「歌词模式」：顶部歌名 + 沉浸歌词；点击收起回封面（iOS 点击封面/歌词
// 互切）。本组件只负责容器与展开/收起按钮，歌词本体见 `ImmersiveLyrics`。

package im.missuo.kumone.feature.player.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import im.missuo.kumone.net.lyrics.ParsedLyrics

/**
 * 歌词面板。
 *
 * @param trackName 曲名（顶部）。
 * @param artistName 歌手（顶部副标题）。
 * @param lyrics 已解析歌词（可空）。
 * @param positionSeconds 当前播放位置。
 * @param showTranslation 显示翻译。
 * @param verbatimLyrics 逐字高亮。
 * @param isScrubbing 触摸中。
 * @param onSeekTo 点击行跳转。
 * @param onScrubbingChange 触摸状态。
 * @param onCollapse 收起歌词回封面。
 */
@Composable
fun LyricsPanel(
    trackName: String,
    artistName: String,
    lyrics: ParsedLyrics?,
    positionSeconds: Double,
    showTranslation: Boolean,
    verbatimLyrics: Boolean,
    isScrubbing: Boolean,
    onSeekTo: (Double) -> Unit,
    onScrubbingChange: (Boolean) -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        // 顶部：曲名 / 歌手 + 收起箭头
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = trackName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = artistName,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.6f),
                    maxLines = 1,
                )
            }
            Box(
                modifier = Modifier
                    .clickable(onClick = onCollapse)
                    .padding(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = "收起歌词",
                    tint = Color.White.copy(alpha = 0.7f),
                )
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
