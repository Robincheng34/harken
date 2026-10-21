// Ported from kumone by missuo (LGPL-3.0)
//
// 播放队列面板（T27 `components/QueuePanel.kt`）——底部上拉的二级页面。
//
// 由宿主以 ModalBottomSheet 承载：从底部上拉，点击遮罩任意位置或下滑即可取消。
// 顶部提供「清空当前播放队列」；当前曲高亮、点击即播、队列为空展示空态。
//
// 行交互（两段式滑动删除，杜绝点击误删）：
//  - 点击歌名 → 播放该曲（行收起时）；行已展开时点击内容 = 收起；
//  - 第一次左滑过阈值 → 行停住在展开位（露出红色删除区），不删除；
//  - 展开后再次左滑过阈值（或点红色区的垃圾桶）→ 才真正删除；
//  - 同一时间最多一行处于展开位（打开新行自动收起旧行）；
//  - 删除/插入/移动经 `Modifier.animateItem()` 做位移补位 + 渐隐过渡，
//    行 key 用「id#出现序号」保证稳定（删除其他行不改变本行 key）。
//
// 撤销快显条：面板内置 inline 实现（不用 M3 SnackbarHost——实测其在本面板的
// ModalBottomSheet 中被 sheet 弹出时固定的内容高度裁剪到可见区之外，日志显示
// 已渲染但屏幕上永远看不见）。

package im.missuo.kumone.feature.player.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import im.missuo.kumone.net.model.Track
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 行展开位宽度：第一次左滑停住后露出的红色删除区宽度。 */
private val RevealWidth = 96.dp

/** 展开后需要再继续左滑的距离，才触发真正的删除。 */
private val DeleteExtra = 56.dp

/** 拖拽的最远距离（向左）。 */
private val MaxDrag = 240.dp

/** 删除红底色（与 iOS 删除一致）。 */
private val DeleteRed = Color(0xFFE5484D)

/** 行内容底色（不透明：否则未滑动时红底会从透明行里透出来）。 */
private val RowBackground = Color(0xFF1C1C1E)

/**
 * 播放队列面板内容（作为底部二级页面内容，由宿主 ModalBottomSheet 承载）。
 *
 * @param tracks 队列（从当前曲之后开始，iOS `upcoming`）。
 * @param totalCount 队列总曲目数（含当前曲，iOS 头部 `N 首`）。
 * @param currentOffset [tracks] 第一首在完整队列里的绝对下标（当前曲下标 + 1）。
 * @param onPlayAt 点击队列中某项（局部下标）。
 * @param onRemoveAt 左滑删除某一项（绝对下标）。
 * @param onInsertAt 撤销删除，把曲目插回当前播放曲之后（引擎实时定位，
 *   不传下标——UI 缓存的绝对下标在队列变化后会过期）。
 * @param onClearQueue 清空当前播放队列（删除当前播放队列按钮）。
 */
@Composable
fun QueuePanel(
    tracks: List<Track>,
    totalCount: Int = tracks.size,
    currentOffset: Int = 0,
    onPlayAt: (Int) -> Unit,
    onRemoveAt: (Int) -> Unit = {},
    onInsertAt: (Track) -> Unit = {},
    onClearQueue: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val scope = rememberCoroutineScope()
    // 待撤销的删除（曲目；绝对下标已弃用——插回位置由引擎实时决定）。
    var undoPending by remember { mutableStateOf<Track?>(null) }
    var undoJob by remember { mutableStateOf<Job?>(null) }
    // 当前展开行的 key（同一时间最多一行展开；null 表示全部收起）。
    var openRowKey by remember { mutableStateOf<String?>(null) }
    // 稳定 key：`id#出现序号`。原 key `"${id}-$index"` 在删除任意一行后
    // 会让所有后续行 key 变化，animateItem() 把「移动」误判成
    // 「删除+新增」，补位过渡动画完全不触发（表现为瞬移）。
    // （remember 须放在 LazyColumn 之外：LazyListScope 块不是 @Composable 上下文。）
    val rowKeys = remember(tracks) {
        val seen = HashMap<Long, Int>()
        tracks.map { t ->
            val n = seen.getOrDefault(t.id, 0)
            seen[t.id] = n + 1
            "${t.id}#$n"
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // 上限 60% 屏高（上限而非固定值： ModalBottomSheet 实际可用高度受
            // 系统 inset 影响可能更小，固定高度会溢出导致列表尾部被裁）。
            .heightIn(max = (screenHeight * 0.6f).coerceAtMost(560.dp))
            .padding(vertical = 8.dp),
    ) {
        // 顶部：标题 + 曲目数 + 清空队列（iOS `Panels.swift:204-210` 头部形态）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "播放队列",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "$totalCount 首",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.5f),
            )
            Spacer(modifier = Modifier.weight(1f))
            // 清空当前播放队列
            Box(
                modifier = Modifier
                    .pointerInput(Unit) { detectTapGestures(onTap = { onClearQueue() }) }
                    .padding(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "清空队列",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        // 头部分隔线（iOS `Divider().opacity(0.3)`）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(Color.White.copy(alpha = 0.15f)),
        )
        Spacer(modifier = Modifier.height(4.dp))

        // 撤销快显条：删除后 4 秒内可一键恢复。放在面板顶部（分隔线下方）——
        // 实测 ModalBottomSheet 弹出时按「无快显条」的内容固定高度，追加在
        // 底部的任何撤销条（M3 SnackbarHost 与 inline 条均试过）都会被裁出
        // 可见区；顶部位置始终可见，观感与 iOS 快显条一致。
        androidx.compose.animation.AnimatedVisibility(
            visible = undoPending != null,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
            val pending = undoPending
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF2C2C2E))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = pending?.let { "已从播放队列移除《${it.name}》" } ?: "",
                    fontSize = 13.sp,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "撤销",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFFF7A7A),
                    modifier = Modifier
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    val state = undoPending ?: return@detectTapGestures
                                    undoJob?.cancel()
                                    undoPending = null
                                    onInsertAt(state)
                                },
                            )
                        }
                        .padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                )
            }
        }

        if (tracks.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "队列为空",
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.5f),
                )
            }
        } else {
            // 列表 + 悬浮 snackbar 用 Box 叠加：ModalBottomSheet 在弹出时按内容
            // 测量并固定高度，之后 SnackbarHost 追加在 Column 尾部会落在 sheet
            // 裁剪区之外（日志显示渲染了、屏幕上却看不见）。悬浮在列表底部
            // 之上则始终可见，也更贴近 iOS 快显条的观感。
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(tracks, key = { index, _ -> rowKeys[index] }) { index, track ->
                        val rowKey = rowKeys[index]
                        val globalIndex = currentOffset + index
                        TwoStageSwipeRow(
                            rowKey = rowKey,
                            track = track,
                            isOpen = openRowKey == rowKey,
                            onOpenChange = { openRowKey = rowKey },
                            onClose = { if (openRowKey == rowKey) openRowKey = null },
                            onPlay = { onPlayAt(index) },
                            onDelete = {
                                // 收起展开态再删除，避免撤销插回后残留展开偏移。
                                if (openRowKey == rowKey) openRowKey = null
                                onRemoveAt(globalIndex)
                                undoPending = track
                                undoJob?.cancel()
                                undoJob = scope.launch {
                                    delay(4_000)
                                    undoPending = null
                                }
                            },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 两段式滑动行：
 *  1. 收起态下第一次左滑过阈值 → 行动画停住在展开位（露出红色删除区），不删除；
 *  2. 展开态下再次左滑超过 [DeleteExtra]（或点红色区垃圾桶）→ [onDelete]；
 *  3. 展开态右滑回阈值以上 → 收起；未过阈值 → 弹回展开位；
 *  4. 点击内容：收起态 → [onPlay]（播放，绝不删除）；展开态 → [onClose]（收起）。
 */
@Composable
private fun TwoStageSwipeRow(
    rowKey: String,
    track: Track,
    isOpen: Boolean,
    onOpenChange: () -> Unit,
    onClose: () -> Unit,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val revealPx = with(density) { RevealWidth.toPx() }
    val deleteExtraPx = with(density) { DeleteExtra.toPx() }
    val maxDragPx = with(density) { MaxDrag.toPx() }
    val scope = rememberCoroutineScope()
    // 行横向位移（px，≤0；负值 = 内容左移露出红底）。
    val offsetX = remember { Animatable(0f) }
    // 本次拖拽开始时行是否处于展开态（决定松手后的判定分支）。
    var dragStartedOpen by remember { mutableStateOf(false) }

    // 外部收起（打开了另一行 / 删除后复位）：把本行动画收回。
    LaunchedEffect(isOpen) {
        if (!isOpen && offsetX.value < 0f) {
            offsetX.animateTo(0f, tween(220))
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        // 红色删除背景（位于内容之下；行展开时从右侧露出）。
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(DeleteRed),
            contentAlignment = Alignment.CenterEnd,
        ) {
            // 垃圾桶：仅展开时可点（第二次确认的另一种方式）。
            Box(
                modifier = Modifier
                    .pointerInput(isOpen) {
                        if (isOpen) detectTapGestures(onTap = { onDelete() })
                    }
                    .padding(end = 24.dp)
                    .size(40.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = Color.White.copy(alpha = if (isOpen) 1f else 0.9f),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .background(RowBackground)
                .pointerInput(Unit) {
                    // 左滑两段式手势（只捕获横向，纵向滚动仍交给 LazyColumn）。
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragStartedOpen = offsetX.value <= -revealPx * 0.5f
                        },
                        onDragEnd = {
                            scope.launch {
                                if (!dragStartedOpen) {
                                    // 第一段：收起态 → 只负责展开/回弹，绝不删除。
                                    if (offsetX.value <= -revealPx * 0.4f) {
                                        offsetX.animateTo(-revealPx, tween(220))
                                        onOpenChange()
                                    } else {
                                        offsetX.animateTo(0f, tween(220))
                                    }
                                } else {
                                    // 第二段：展开态 → 继续左滑过阈值才删除。
                                    when {
                                        offsetX.value <= -(revealPx + deleteExtraPx) -> onDelete()
                                        offsetX.value >= -revealPx * 0.4f -> {
                                            offsetX.animateTo(0f, tween(220))
                                            onClose()
                                        }
                                        else -> offsetX.animateTo(-revealPx, tween(220))
                                    }
                                }
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val target = (offsetX.value + dragAmount)
                                .coerceIn(-maxDragPx, 0f)
                            scope.launch { offsetX.snapTo(target) }
                        },
                    )
                }
                .pointerInput(isOpen) {
                    // 点击：收起态播放（绝不删除）；展开态收起。
                    detectTapGestures(onTap = { if (isOpen) onClose() else onPlay() })
                }
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            Text(
                text = track.name,
                fontSize = 14.sp,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artistNames,
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
