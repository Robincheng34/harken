// Ported from kumone by missuo (LGPL-3.0)
//
// 发现页歌单网格（T19 `components/PlaylistGrid.kt`，iOS `Cards.swift` `CardGrid`
// compact 形态 1:1 移植）。
//
// 自适应最小列宽 140dp（架构文档 T19 规格；等价 iOS compact 双列）；封面撑满列宽
// 并保持 1:1（iOS `flexibleCardWidth`）+ 播放量角标 + 播放悬浮钮；逐个入场
// （iOS `staggeredAppearance(index: index % 10)`）。

package im.missuo.kumone.feature.explore.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import im.missuo.kumone.design.component.CoverCard
import im.missuo.kumone.design.component.staggeredAppearance
import im.missuo.kumone.net.model.Formatters
import im.missuo.kumone.net.model.PlaylistSummary

/**
 * 歌单自适应网格。
 *
 * @param items 歌单列表。
 * @param onOpen 点击卡片（传入歌单 id）。
 * @param onPlay 播放悬浮钮（传入歌单 id）；传 null 不展示。
 * @param staggered 是否逐个入场。
 */
@Composable
fun PlaylistGrid(
    items: List<PlaylistSummary>,
    onOpen: (Long) -> Unit,
    onPlay: ((Long) -> Unit)? = null,
    staggered: Boolean = true,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp), // 架构文档 T19：minWidth 140dp
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), // iOS compact: spacing 16
        verticalArrangement = Arrangement.spacedBy(20.dp), // iOS compact: spacing 20
    ) {
        itemsIndexed(items, key = { _, playlist -> playlist.id }) { index, playlist ->
            CoverCard(
                imageUrl = Formatters.resizedImageURL(playlist.coverURL, 384),
                contentDescription = playlist.name,
                title = playlist.name,
                subtitle = playlist.copywriter,
                size = null, // 撑满列宽（iOS flexibleCardWidth）
                playCount = playlist.playCount,
                onPlay = onPlay?.let { callback -> { callback(playlist.id) } },
                onClick = { onOpen(playlist.id) },
                modifier = Modifier.staggeredAppearance(index = index % 10, enabled = staggered),
            )
        }
    }
}
