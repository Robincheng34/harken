// Ported from kumone by missuo (LGPL-3.0)
//
// 沉浸歌词（T27 `components/ImmersiveLyrics.kt`）。
//
// 1:1 复刻 iOS 沉浸歌词交互：
//  - 当前行大字高亮（28sp，`LyricLineActiveText`），邻行缩小变暗；
//  - 点击任意行跳转到该行时间（由 [onSeekTo] 回调交给引擎 seekTo）；
//  - 触摸歌词暂停自动滚动（[isScrubbing]），抬手恢复（iOS 同名行为）；
//  - 逐字歌词（YRC）按词高亮，未开启时整行高亮。

package im.missuo.kumone.feature.player.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import im.missuo.kumone.net.lyrics.LyricLine
import im.missuo.kumone.net.lyrics.ParsedLyrics

/** 当前行字号（iOS 沉浸歌词 28sp）。 */
private val ActiveLineSize = 22.sp

/** 非当前行字号。 */
private val InactiveLineSize = 15.sp

/**
 * 沉浸歌词列表。
 *
 * @param lyrics 已解析歌词。
 * @param positionSeconds 当前播放位置（秒）。
 * @param showTranslation 是否显示翻译。
 * @param verbatimLyrics 是否逐字高亮。
 * @param isScrubbing 已废弃：暂停自动滚动现由列表滚动状态内部驱动（保留参数
 *   以维持调用方签名兼容）。
 * @param onSeekTo 点击某行时跳转（秒）。
 * @param onScrubbingChange 滚动开始/结束（暂停自动滚动的对外通知）。
 */
@Composable
fun ImmersiveLyrics(
    lyrics: ParsedLyrics?,
    positionSeconds: Double,
    showTranslation: Boolean,
    verbatimLyrics: Boolean,
    isScrubbing: Boolean,
    onSeekTo: (Double) -> Unit,
    onScrubbingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (lyrics == null || lyrics.lines.isEmpty()) {
        EmptyLyrics(modifier = modifier)
        return
    }
    if (lyrics.isInstrumental) {
        Column(
            modifier = modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "纯音乐，请欣赏",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
        return
    }

    val listState = rememberLazyListState()
    // 原 bug：derivedStateOf 放在 remember(lyrics, positionSeconds) 内——
    // positionSeconds 每 tick 变化导致 derivedStateOf 每 tick 重建，失去派生
    // 缓存意义。现移入 remember(lyrics) 一次性创建，位置经 rememberUpdatedState
    // 以 State 形式在派生内读取：仅当 activeIndex 结果变化时才触发重组。
    val currentPosition by rememberUpdatedState(positionSeconds)
    val activeIndex by remember(lyrics) {
        derivedStateOf { lyrics.activeIndex(currentPosition) }
    }

    // 滑动中暂停自动滚动：改由列表自身滚动状态驱动（derivedStateOf）。
    // 原 bug：detectVerticalDragGestures 的 pointerInput 与内层列表滚动竞争
    // 消费事件，滑动暂停检测不可靠。
    val isUserScrolling by remember { derivedStateOf { listState.isScrollInProgress } }
    LaunchedEffect(isUserScrolling) { onScrubbingChange(isUserScrolling) }

    // 自动滚动到当前行（用户滑动中暂停；iOS 同）。
    LaunchedEffect(activeIndex, isUserScrolling) {
        val index = activeIndex ?: return@LaunchedEffect
        if (isUserScrolling) return@LaunchedEffect
        listState.animateScrollToItem(index.coerceAtLeast(0))
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 96.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        itemsIndexed(
            items = lyrics.lines,
            key = { _, line -> line.id },
        ) { _, line ->
            LyricLineRow(
                line = line,
                active = line.id == activeIndex,
                // 逐字高亮只有当前 active 行需要精确位置（原 bug：每行都传最新
                // 位置导致全部行每 tick 重组）；非 active 行传自身行时间即可。
                positionSeconds = if (line.id == activeIndex) positionSeconds else line.time,
                showTranslation = showTranslation,
                verbatimLyrics = verbatimLyrics,
                onClick = { onSeekTo(line.time) },
            )
        }
    }
}

/** 单行歌词（当前行大字 + 翻译 + 逐字）。 */
@Composable
private fun LyricLineRow(
    line: LyricLine,
    active: Boolean,
    positionSeconds: Double,
    showTranslation: Boolean,
    verbatimLyrics: Boolean,
    onClick: () -> Unit,
) {
    val size = if (active) ActiveLineSize else InactiveLineSize
    val color = if (active) Color.White else Color.White.copy(alpha = 0.45f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val words = line.words
        if (verbatimLyrics && active && !words.isNullOrEmpty()) {
            // 逐字歌词：已唱过的词高亮为品牌红，当前词白色。
            val wordList = words
            val activeWordIndex = wordList.indexOfFirst { positionSeconds < it.end }
                .let { if (it < 0) wordList.size else it }
            val annotated = buildAnnotatedString {
                wordList.forEachIndexed { index, word ->
                    withStyle(
                        SpanStyle(
                            color = if (index < activeWordIndex) {
                                im.missuo.kumone.design.theme.BrandRedBright
                            } else {
                                color
                            },
                        ),
                    ) {
                        append(word.text)
                    }
                }
            }
            Text(
                text = annotated,
                fontSize = size,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
            )
        } else {
            Text(
                text = line.text,
                fontSize = size,
                color = color,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
            )
        }

        if (showTranslation && !line.translation.isNullOrEmpty()) {
            Text(
                text = line.translation!!,
                fontSize = if (active) 14.sp else 12.sp,
                color = Color.White.copy(alpha = if (active) 0.7f else 0.35f),
                textAlign = TextAlign.Center,
            )
        }
        if (active && !line.romaji.isNullOrEmpty()) {
            Text(
                text = line.romaji!!,
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 无歌词占位。 */
@Composable
private fun EmptyLyrics(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().background(Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "暂无歌词",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.6f),
        )
    }
}
