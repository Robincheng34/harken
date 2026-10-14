// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.design.component

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import im.missuo.kumone.net.model.Track

/**
 * 曲目列表（iOS `TrackListView` 1:1 移植）。
 *
 * 单一 [LazyColumn] 承载曲目；[currentTrackId] 高亮当前曲。其余列表项（分区头、骨架）
 * 通过 [header]/[footer] 由调用方在 [LazyListScope] 中插入。
 */
@Composable
fun TrackListView(
    tracks: List<Track>,
    currentTrackId: Long? = null,
    onPlay: (track: Track, index: Int) -> Unit,
    modifier: Modifier = Modifier,
    header: (LazyListScope.() -> Unit)? = null,
    footer: (LazyListScope.() -> Unit)? = null,
) {
    LazyColumn(modifier = modifier) {
        header?.invoke(this)
        // 原 bug：key 用 t.id，列表含重复歌曲时抛 duplicate key 崩溃；
        // 改为 id+index 保证唯一。
        itemsIndexed(tracks, key = { index, t -> "${t.id}-$index" }) { index, track ->
            TrackRow(
                track = track,
                index = index + 1,
                isCurrent = track.id == currentTrackId,
                onPlay = { onPlay(track, index) },
            )
        }
        footer?.invoke(this)
    }
}
