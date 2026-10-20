// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import im.missuo.kumone.net.model.Track

/**
 * 曲目列表（iOS `TrackListView` 1:1 移植）。
 *
 * 单一 [LazyColumn] 承载曲目；[currentTrackId] 高亮当前曲。其余列表项（分区头、骨架）
 * 通过 [header]/[footer] 由调用方在 [LazyListScope] 中插入。
 *
 * [onDelete] 非空时开启「向左滑删除」（目前仅最近播放页使用）：每行被
 * [SwipeToDismissBox] 包裹，向左滑露出红色删除背景，滑到底触发 [onDelete]。
 * 默认 null 时与旧行为完全一致——每日推荐 / 音乐云盘 / 搜索结果等调用点不出现滑动。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackListView(
    tracks: List<Track>,
    currentTrackId: Long? = null,
    onPlay: (track: Track, index: Int) -> Unit,
    modifier: Modifier = Modifier,
    header: (LazyListScope.() -> Unit)? = null,
    footer: (LazyListScope.() -> Unit)? = null,
    onDelete: ((Track) -> Unit)? = null,
) {
    LazyColumn(modifier = modifier) {
        header?.invoke(this)
        // 原 bug：key 用 t.id，列表含重复歌曲时抛 duplicate key 崩溃；
        // 改为 id+index 保证唯一。删除后索引变化会换 key，滑动状态不会被错位复用。
        itemsIndexed(tracks, key = { index, t -> "${t.id}-$index" }) { index, track ->
            if (onDelete == null) {
                TrackRow(
                    track = track,
                    index = index + 1,
                    isCurrent = track.id == currentTrackId,
                    onPlay = { onPlay(track, index) },
                )
            } else {
                SwipeToDeleteTrackRow(
                    track = track,
                    index = index,
                    isCurrent = track.id == currentTrackId,
                    onPlay = { onPlay(track, index) },
                    onDelete = onDelete,
                )
            }
        }
        footer?.invoke(this)
    }
}

/**
 * 可左滑删除的曲目行（仅 [TrackListView] 传入 [onDelete] 时使用）。
 *
 * 背景层是红色 + 垃圾桶图标（右对齐，即手指划走后露出的那一侧）；内容层是不透明的
 * [TrackRow]。向左滑到底触发 [onDelete]；反向（向右）滑动已禁用，避免误触。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteTrackRow(
    track: Track,
    index: Int,
    isCurrent: Boolean,
    onPlay: () -> Unit,
    onDelete: (Track) -> Unit,
) {
    // 列表 key 为 id+index：行内容一变就换新状态，不会残留上一次的「已滑出」状态。
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete(track)
                true
            } else {
                false
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.error)
                    .padding(end = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.onError,
                )
            }
        },
        content = {
            // 内容层必须不透明，否则未滑动时也会透出红色删除背景。
            // 取 background（Scaffold 容器色）而非 surface，保证与列表底色一致。
            TrackRow(
                track = track,
                index = index + 1,
                isCurrent = isCurrent,
                onPlay = onPlay,
                modifier = Modifier.background(MaterialTheme.colorScheme.background),
            )
        },
    )
}
