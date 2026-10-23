// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 沉浸歌词（T27 `components/ImmersiveLyrics.kt`）。
//
// 1:1 复刻 iOS 沉浸歌词交互：
//  - 当前行大字高亮（28sp，`LyricLineActiveText`），邻行缩小变暗；
//  - 点击任意行跳转到该行时间（由 [onSeekTo] 回调交给引擎 seekTo）；
//  - 触摸歌词暂停自动滚动（[isScrubbing]），抬手恢复（iOS 同名行为）；
//  - 逐字歌词（YRC）按词高亮，未开启时整行高亮。

package com.rc.harken.feature.player.components

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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import com.rc.harken.net.lyrics.LyricLine
import com.rc.harken.net.lyrics.ParsedLyrics
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 当前行字号（iOS 沉浸歌词 28sp）。 */
private val ActiveLineSize = 22.sp

/** 非当前行字号。 */
private val InactiveLineSize = 15.sp

/**
 * 单行高亮过渡时长（字号 / 透明度 / 颜色渐变，避免「啪」地切换）。
 * `animateScrollToItem` 在本项目依赖的 Compose Foundation 1.10 中仅接受
 * (index, scrollOffset)，无 animationSpec 入参，故滚动沿用其内置 spring；
 * 丝滑感主要由下面的逐行渐变提供。
 */
private const val LineTransitionMs = 280

/**
 * 沉浸歌词列表。
 *
 * @param lyrics 已解析歌词。
 * @param positionSeconds 当前播放位置（秒）。
 * @param showTranslation 是否显示翻译。
 * @param verbatimLyrics 已废弃：逐字红色高亮因每 tick 重组导致卡顿已移除，
 *   当前行改为整体高亮（保留参数以维持调用方签名兼容）。
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
    // 滑动检测：区分「用户手动滑动」与「程序自动滚动」。
    // isScrollInProgress 对两者都为真；程序滚动（自动跟唱 / 回弹）期间置
    // programmatic=true，否则回弹自身的 animateScrollToItem 会把
    // isUserScrolling 重新置真 → userScrolled 永远为真 → 回弹每 5 秒自触发
    // 且自动跟唱被永久抑制（原 bug）。
    val isUserScrolling by remember { derivedStateOf { listState.isScrollInProgress } }
    LaunchedEffect(isUserScrolling) { onScrubbingChange(isUserScrolling) }

    var userScrolled by remember { mutableStateOf(false) }
    var programmatic by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var scrollBackJob by remember { mutableStateOf<Job?>(null) }

    // 用户手动滑动结束（isScrollInProgress 由真转假）后，等待 5 秒再平滑滚动回
    // 当前播放行；期间再次滑动会取消等待并重新计时。
    LaunchedEffect(isUserScrolling) {
        if (programmatic) return@LaunchedEffect
        if (isUserScrolling) {
            userScrolled = true
            scrollBackJob?.cancel()
        } else if (userScrolled) {
            scrollBackJob = scope.launch {
                delay(5000)
                val idx = activeIndex ?: return@launch
                programmatic = true
                try {
                    listState.animateScrollToItem(idx.coerceAtLeast(0))
                } finally {
                    userScrolled = false
                    programmatic = false
                }
            }
        }
    }

    // 正常跟唱（用户未手动滑动时）：当前行变化立即平滑滚动跟随。
    LaunchedEffect(activeIndex) {
        if (!userScrolled && !listState.isScrollInProgress) {
            val idx = activeIndex ?: return@LaunchedEffect
            programmatic = true
            try {
                listState.animateScrollToItem(idx.coerceAtLeast(0))
            } finally {
                programmatic = false
            }
        }
    }

    // 换歌重置：上一首遗留的「用户滑动过」标记会让新歌的自动跟唱被抑制到回弹结束，
    // 表现为切歌后歌词不自动滚到当前行。此处随 lyrics 变化一并清空。
    LaunchedEffect(lyrics) {
        scrollBackJob?.cancel()
        userScrolled = false
        programmatic = false
    }

    // 离开页面时取消回弹定时器：`scope`（rememberCoroutineScope）虽随组合销毁而取消，
    // 但显式置空可避免 Job 引用在重组间残留。
    DisposableEffect(Unit) {
        onDispose { scrollBackJob?.cancel() }
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
                showTranslation = showTranslation,
                onClick = { onSeekTo(line.time) },
            )
        }
    }
}

/** 单行歌词（当前行大字 + 翻译 + 罗马音）。 */
@Composable
private fun LyricLineRow(
    line: LyricLine,
    active: Boolean,
    showTranslation: Boolean,
    onClick: () -> Unit,
) {
    // 当前行 0→1 的过渡进度：字号 / 透明度随之平滑插值，避免「啪」地切换。
    val progress by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(durationMillis = LineTransitionMs, easing = FastOutSlowInEasing),
        label = "lyricLineActive",
    )
    val fontSize = (InactiveLineSize.value + (ActiveLineSize.value - InactiveLineSize.value) * progress).sp
    val color = Color.White.copy(alpha = 0.45f + 0.55f * progress)
    val fontWeight = if (progress > 0.5f) FontWeight.Bold else FontWeight.Normal

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 逐字红色高亮已移除：YRC 每个 tick 重建 AnnotatedString（已唱词染红）会让
        // 当前行每帧重组，直接表现为歌词卡顿。当前行仅以「放大 + 变亮 + 加粗」整体
        // 高亮，无逐字变色，也就无每 tick 重组。
        Text(
            text = line.text,
            fontSize = fontSize,
            color = color,
            fontWeight = fontWeight,
            textAlign = TextAlign.Center,
        )

        if (showTranslation && !line.translation.isNullOrEmpty()) {
            Text(
                text = line.translation!!,
                fontSize = (12.sp.value + 2.sp.value * progress).sp,
                color = Color.White.copy(alpha = 0.35f + 0.35f * progress),
                textAlign = TextAlign.Center,
            )
        }
        if (progress > 0.01f && !line.romaji.isNullOrEmpty()) {
            Text(
                text = line.romaji!!,
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.5f * progress),
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
