// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.design.component

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
 * [SwipeToDeleteRow] 包裹，向左滑露出红色删除背景，滑到底触发 [onDelete]。
 * [guard] 为真时启用「删除确认」：左滑停顿出现红条，需继续滑动或点击红区才删除
 * （来自设置「高级」项，默认关）。
 * 默认 null 时与旧行为完全一致——每日推荐 / 音乐云盘 / 搜索结果等调用点不出现滑动。
 */
@Composable
fun TrackListView(
    tracks: List<Track>,
    currentTrackId: Long? = null,
    onPlay: (track: Track, index: Int) -> Unit,
    modifier: Modifier = Modifier,
    header: (LazyListScope.() -> Unit)? = null,
    footer: (LazyListScope.() -> Unit)? = null,
    onDelete: ((Track) -> Unit)? = null,
    guard: Boolean = false,
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
                    guard = guard,
                )
            }
        }
        footer?.invoke(this)
    }
}

/**
 * 可左滑删除的曲目行（仅 [TrackListView] 传入 [onDelete] 时使用）。
 *
 * 直接复用统一的 [SwipeToDeleteRow]：红色删除背景 + 右侧垃圾桶、点击播放 / 左滑删除手势
 * 分离、显式慢动画；内容层 [TrackRow] 以列表底色作不透明遮罩，滑出才露出红条。
 * [guard] 为真时启用「删除确认」（停顿后需继续滑或点红区才删）。
 */
@Composable
private fun SwipeToDeleteTrackRow(
    track: Track,
    index: Int,
    isCurrent: Boolean,
    onPlay: () -> Unit,
    onDelete: (Track) -> Unit,
    guard: Boolean = false,
) {
    SwipeToDeleteRow(
        guard = guard,
        onPlay = onPlay,
        onDelete = { onDelete(track) },
        contentBackground = MaterialTheme.colorScheme.background,
    ) {
        TrackRow(
            track = track,
            index = index + 1,
            isCurrent = isCurrent,
            onPlay = onPlay,
        )
    }
}
