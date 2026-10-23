// Ported from kumone by missuo (LGPL-3.0)
//
// 歌单横滑 shelf（共享组件，T18 起由 `:design-system` 提供，供首页/发现/搜索复用）。
//
// 封面 384 缩放 + 播放量角标 + 播放悬浮钮（[onPlay] 非空时）；标题两行 / 副标题
// （copywriter）一行；可选逐个入场（[staggered]，iOS `staggeredAppearance`）。

package im.missuo.kumone.design.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import im.missuo.kumone.design.theme.Layout
import im.missuo.kumone.net.model.Formatters
import im.missuo.kumone.net.model.PlaylistSummary

/**
 * 歌单横滑 shelf。
 *
 * @param title 分区标题。
 * @param items 歌单列表。
 * @param onOpen 点击卡片（传入歌单 id）。
 * @param onPlay 播放悬浮钮（传入歌单 id）；传 null 不展示悬浮钮。
 * @param staggered 是否逐个入场（iOS `staggeredAppearance`）。
 */
@Composable
fun PlaylistShelf(
    title: String,
    items: List<PlaylistSummary>,
    onOpen: (Long) -> Unit,
    onPlay: ((Long) -> Unit)? = null,
    staggered: Boolean = false,
    modifier: Modifier = Modifier,
) {
    SectionHeader(title = title)
    if (items.isEmpty()) return
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(items, key = { _, playlist -> playlist.id }) { index, playlist ->
            CoverCard(
                imageUrl = Formatters.resizedImageURL(playlist.coverURL, 384),
                contentDescription = playlist.name,
                title = playlist.name,
                subtitle = playlist.copywriter,
                size = Layout.cardSize,
                playCount = playlist.playCount,
                onPlay = onPlay?.let { callback -> { callback(playlist.id) } },
                onClick = { onOpen(playlist.id) },
                modifier = Modifier.staggeredAppearance(index = index, enabled = staggered),
                // copywriter 可能为空 → 固定预留副标题行，保证整行等高（否则横滑时
                // 行高变化会让下方分区跳动）
                reserveSubtitleLine = true,
            )
        }
    }
}
