// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 播放队列面板（官方客户端做法重做）。
//
// 取证依据（网易云官方 APK 反编译产物）：
//  - PlayerListDialog2 / PlayerListCurrentFragment：面板 = 当前播放列表 BottomSheet，
//    头部含清空按钮（mClearIconView），随机模式下有「回到原始顺序」开关
//    （playListSwitchToOriginOrder）；
//  - PlayerListItemViewHolder：行 = 封面缩略图 + 曲名（VIP 角标）+ 歌手 + 时长，
//    当前曲行有播放动效标记（官方为 Lottie，此处用自绘均衡器等价实现）；
//  - SwipeMenuLayout + ItemTouchHelper.onSwiped 空实现：删除 = 左滑出菜单点删，
//    不是滑动即删；
//  - 内部类 l extends ItemTouchHelper.Callback：长按上下拖拽排序（makeMovementFlags
//    仅 UP|DOWN、isLongPressDragEnabled、触觉反馈）。
//
// 行交互：
//  - 点击非当前曲行 → 从该绝对下标播放（当前曲行点击无动作）；
//  - 左滑（仅非当前曲行）→ 停在展开位露出「删除」按钮，点按钮才删除；
//  - 长按任意行上下拖拽 → 实时重排（每跨一行提交一次 onMove，其余行经
//    animateItem 平滑补位），带触觉反馈；
//  - 随机模式下头部出现「回到原始顺序」开关（打开即恢复原始顺序）。

package com.rc.harken.feature.player.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.rc.harken.net.model.Formatters
import com.rc.harken.net.model.Track
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** 官方品牌红（当前曲高亮 / VIP 角标 / 删除按钮）。 */
private val OfficialRed = Color(0xFFEC4141)

/** 行内容底色（不透明，遮住删除红底）。 */
private val RowBackground = Color(0xFF1C1C1E)

/** 左滑菜单宽度 / 触发展开阈值。 */
private val SwipeMenuWidth = 72.dp
private val SwipeOpenThreshold = 36.dp

/**
 * 播放队列面板（官方做法：完整队列 + 当前曲原位高亮 + 左滑出菜单点删 + 长按拖拽排序）。
 *
 * @param tracks 完整队列（含当前曲）。
 * @param currentIndex 当前曲下标（-1 = 无）。
 * @param onPlayAt 点击非当前曲行：从该绝对下标播放。
 * @param onRemoveAt 左滑菜单点「删除」：移除该绝对下标。
 * @param onMove 拖拽排序提交 from → to。
 * @param onClearQueue 头部清空（整表清空并停止）。
 * @param onRestoreOrder 「回到原始顺序」开关打开（= 恢复原始顺序 / 关闭随机）。
 * @param shuffleEnabled 随机模式标记（控制「回到原始顺序」行的显隐）。
 */
@Composable
fun QueuePanel(
    tracks: List<Track>,
    currentIndex: Int,
    onPlayAt: (Int) -> Unit,
    onRemoveAt: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onClearQueue: () -> Unit,
    onRestoreOrder: () -> Unit,
    shuffleEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    // 稳定 key：`id#出现序号`（id 重复时区分出现次序；删除其他行不改变本行 key，
    // animateItem 补位动画依赖 key 稳定）。
    val rowKeys = remember(tracks) {
        val seen = HashMap<Long, Int>()
        tracks.map { t ->
            val n = seen.getOrDefault(t.id, 0)
            seen[t.id] = n + 1
            "${t.id}#$n"
        }
    }
    // 长按拖拽状态（live-swap：每跨一行即提交一次 onMove，引擎重排后
    // animateItem 让其余行平滑补位；被拖行用 translationY 保持视觉连续）。
    var dragFromIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    // 各行实测高度（拖拽跨行阈值用被拖行自己的高度；按 rowKey 索引——用 index
    // 会在删除行后张冠李戴，且只增不减）。
    val rowHeights = remember { mutableMapOf<String, Int>() }
    // 左滑展开的唯一行 key。
    var openRowKey by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = (screenHeight * 0.6f).coerceAtMost(560.dp))
            .padding(vertical = 8.dp),
    ) {
        // 头部：当前播放 + 计数 + 清空（官方 PlayerListDialog2 头部形态）。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "当前播放",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "${tracks.size} 首",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.5f),
            )
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .pointerInput(Unit) { detectTapGestures(onTap = { onClearQueue() }) }
                    .padding(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "清空",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        // 「回到原始顺序」（随机模式下出现；打开 = 恢复原始顺序，即关闭随机）。
        if (shuffleEnabled) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "回到原始顺序",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = false,
                    onCheckedChange = { checked -> if (checked) onRestoreOrder() },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = OfficialRed,
                        checkedThumbColor = Color.White,
                    ),
                    modifier = Modifier.height(28.dp),
                )
            }
        }
        // 头部分隔线。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(Color.White.copy(alpha = 0.15f)),
        )
        Spacer(modifier = Modifier.height(4.dp))

        if (tracks.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "播放队列是空的",
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.5f),
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                itemsIndexed(tracks, key = { index, _ -> rowKeys[index] }) { index, track ->
                    val isCurrent = index == currentIndex
                    val rowKey = rowKeys[index]
                    val isDragging = dragFromIndex == index
                    val rowHeightPx = rowHeights[rowKey]
                        ?: with(density) { 64.dp.toPx() }.toInt()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem()
                            .zIndex(if (isDragging) 1f else 0f)
                            .let { m ->
                                if (isDragging) {
                                    m.graphicsLayer { translationY = dragOffsetY }
                                } else {
                                    m
                                }
                            }
                            .onSizeChanged { rowHeights[rowKey] = it.height },
                    ) {
                        OfficialQueueRow(
                            track = track,
                            isCurrent = isCurrent,
                            isDragging = isDragging,
                            // 展开中的行仍保留左滑手势（右滑收回，官方 SwipeMenuLayout
                            // 语义）；其他行在别行展开时禁滑（同时最多一行展开）。
                            swipeEnabled = !isCurrent && (openRowKey == null || openRowKey == rowKey),
                            open = openRowKey == rowKey,
                            onOpenChange = { open -> openRowKey = if (open) rowKey else null },
                            haptics = haptics,
                            density = density,
                            onPlay = if (isCurrent) null else ({ onPlayAt(index) }),
                            onDelete = {
                                if (openRowKey == rowKey) openRowKey = null
                                onRemoveAt(index)
                            },
                            onDragStart = {
                                // 左滑展开时禁止拖拽排序，两种手势不混用。
                                if (openRowKey == null) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    dragFromIndex = index
                                    dragOffsetY = 0f
                                }
                            },
                            onDrag = { amount ->
                                if (dragFromIndex != null) {
                                    dragOffsetY += amount
                                    val step = rowHeightPx.toFloat()
                                    // 每跨一行立即提交一次重排（live swap）。
                                    scope.launch {
                                        while (dragOffsetY > step / 2) {
                                            val fromNow = dragFromIndex ?: break
                                            val to = fromNow + 1
                                            if (to > tracks.lastIndex) break
                                            onMove(fromNow, to)
                                            dragFromIndex = to
                                            dragOffsetY -= step
                                        }
                                        while (dragOffsetY < -step / 2) {
                                            val fromNow = dragFromIndex ?: break
                                            val to = fromNow - 1
                                            if (to < 0) break
                                            onMove(fromNow, to)
                                            dragFromIndex = to
                                            dragOffsetY += step
                                        }
                                    }
                                }
                            },
                            onDragEnd = {
                                dragFromIndex = null
                                dragOffsetY = 0f
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 官方式队列行：封面缩略图 + 曲名（VIP 角标）+ 歌手 + 行尾（动效/时长），
 * 左滑出「删除」菜单（官方 SwipeMenuLayout 语义：滑出菜单点删，非滑动即删）。
 */
@Composable
private fun OfficialQueueRow(
    track: Track,
    isCurrent: Boolean,
    isDragging: Boolean,
    swipeEnabled: Boolean,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    haptics: HapticFeedback,
    density: Density,
    onPlay: (() -> Unit)?,
    onDelete: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val menuPx = with(density) { SwipeMenuWidth.toPx() }
    val openThresholdPx = with(density) { SwipeOpenThreshold.toPx() }
    // 回调经 rememberUpdatedState 解引用：pointerInput 不因 index/列表变化重启
    // （拖拽手势不能中断），闭包必须始终调用「最新一次组合」的回调——否则行
    // 移动后闭包里的旧 index/旧 tracks 会让 live-swap 提交错位（实测拖拽失效）。
    val currentOnOpenChange by rememberUpdatedState(onOpenChange)
    val currentOnPlay by rememberUpdatedState(onPlay)
    val currentOnDelete by rememberUpdatedState(onDelete)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)

    // 展开状态由面板统一管理：外部收起（打开了另一行）时把本行动画归位。
    LaunchedEffect(open) {
        if (!open && offsetX.value != 0f) {
            offsetX.animateTo(0f, tween(200))
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        // 右侧红底「删除」菜单（官方：左滑出菜单，点删）。
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(OfficialRed),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                text = "删除",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .pointerInput(open) {
                        if (open) {
                            detectTapGestures(onTap = { currentOnDelete() })
                        }
                    }
                    .padding(end = 24.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                // 拖拽中的行浮起（阴影），松手后消失——官方 ItemTouchHelper 视觉。
                .shadow(elevation = if (isDragging) 8.dp else 0.dp)
                .background(RowBackground)
                .pointerInput(isCurrent, swipeEnabled) {
                    if (!isCurrent && swipeEnabled) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                scope.launch {
                                    if (open) {
                                        // 已展开：右滑过阈值收回，否则回弹到展开位。
                                        if (offsetX.value > -menuPx * 0.6f) {
                                            offsetX.animateTo(0f, tween(200))
                                            currentOnOpenChange(false)
                                        } else {
                                            offsetX.animateTo(-menuPx, tween(200))
                                        }
                                    } else if (abs(offsetX.value) >= openThresholdPx) {
                                        offsetX.animateTo(-menuPx, tween(200))
                                        currentOnOpenChange(true)
                                    } else {
                                        offsetX.animateTo(0f, tween(200))
                                        currentOnOpenChange(false)
                                    }
                                }
                            },
                            onDragCancel = {
                                scope.launch { offsetX.animateTo(0f, tween(200)) }
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                val target = (offsetX.value + dragAmount)
                                    .coerceIn(-menuPx, 0f)
                                scope.launch { offsetX.snapTo(target) }
                            },
                        )
                    }
                }
                .pointerInput(open) {
                    // 长按拖拽排序（官方 ItemTouchHelper 长按语义）。
                    // key 只用 open：isDragging 不能进 key——dragStart 置位后
                    // key 变化会重启 pointerInput、把手势中途杀掉（实测拖拽失效）。
                    if (!open) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { currentOnDragStart() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                currentOnDrag(dragAmount.y)
                            },
                            onDragEnd = { currentOnDragEnd() },
                            onDragCancel = { currentOnDragEnd() },
                        )
                    }
                }
                .pointerInput(isCurrent, open) {
                    detectTapGestures(
                        onTap = {
                            val play = currentOnPlay
                            when {
                                open -> currentOnOpenChange(false)
                                play != null -> play()
                            }
                        },
                    )
                }
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 封面缩略图（官方行规格）。
            AsyncImage(
                model = track.album.picUrl?.let { Formatters.resizedImageURL(it, 96) },
                contentDescription = null,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White.copy(alpha = 0.08f)),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = track.name,
                        fontSize = 13.sp,
                        fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                        color = if (isCurrent) OfficialRed else Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // VIP 角标（官方 isVipSong：fee == 1）。
                    if (track.fee == 1) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "VIP",
                            fontSize = 9.sp,
                            color = OfficialRed,
                            modifier = Modifier
                                .clip(RoundedCornerShape(2.dp))
                                .background(OfficialRed.copy(alpha = 0.12f))
                                .padding(horizontal = 3.dp, vertical = 1.dp),
                        )
                    }
                }
                Text(
                    text = track.artistNames,
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 行尾：当前曲播放动效（官方为 Lottie，等价自绘均衡器）/ 时长。
            Box(modifier = Modifier.width(32.dp), contentAlignment = Alignment.CenterEnd) {
                if (isCurrent) {
                    PlayingBars()
                } else {
                    Text(
                        text = formatDuration(track.durationMS),
                        fontSize = 10.5.sp,
                        color = Color.White.copy(alpha = 0.4f),
                    )
                }
            }
        }
    }
}

/** 当前曲播放动效：3 根竖条均衡器（官方 Lottie 标记的等价实现）。 */
@Composable
private fun PlayingBars(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "playingBars")
    val bars = (0..2).map { i ->
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 420),
                repeatMode = RepeatMode.Reverse,
                initialStartOffset = StartOffset(i * 160),
            ),
            label = "bar$i",
        )
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        bars.forEach { h ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((10f * h.value).dp)
                    .background(OfficialRed, RoundedCornerShape(1.dp)),
            )
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0) return ""
    val totalSeconds = durationMs / 1000
    return if (totalSeconds >= 3600) {
        // 长音频（播客/电台）用 h:mm:ss，避免 61:23 这种歧义显示。
        "%d:%02d:%02d".format(totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60)
    } else {
        "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }
}
