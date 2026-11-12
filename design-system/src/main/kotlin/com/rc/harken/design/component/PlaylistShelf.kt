// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 歌单横滑 shelf（共享组件，T18 起由 `:design-system` 提供，供首页/发现/搜索复用）。
//
// 封面 384 缩放 + 播放量角标 + 播放悬浮钮（[onPlay] 非空时）；标题两行 / 副标题
// （copywriter）一行；可选逐个入场（[staggered]，iOS `staggeredAppearance`）。

package com.rc.harken.design.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rc.harken.design.theme.Layout
import com.rc.harken.net.model.Formatters
import com.rc.harken.net.model.PlaylistSummary

/**
 * 歌单横滑 shelf。
 *
 * @param title 分区标题。
 * @param items 歌单列表。
 * @param onOpen 点击卡片（传入歌单 id）。
 * @param onPlay 播放悬浮钮（传入歌单 id）；传 null 不展示悬浮钮。
 * @param staggered 是否逐个入场（iOS `staggeredAppearance`）。
 * @param showHeader 是否由本组件自绘分区标题（标题 + 右侧「>」箭头）。调用方已在
 *   外层画过标题（如搜索结果分区、详情页「热门专辑」）时传 false，避免出现一条
 *   空白标题行 + 多余箭头。默认 true，保持既有调用点行为不变。
 */
@Composable
fun PlaylistShelf(
    title: String,
    items: List<PlaylistSummary>,
    onOpen: (Long) -> Unit,
    onPlay: ((Long) -> Unit)? = null,
    staggered: Boolean = false,
    showHeader: Boolean = true,
    modifier: Modifier = Modifier,
) {
    // 标题与箭头一起受 showHeader 控制（title 为空时也要能显式关掉空标题行）。
    if (showHeader) {
        SectionHeader(title = title, showChevron = true)
    }
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
                // 不显示 copywriter 小字（用户要求：推荐歌单下方的小字去掉）
                subtitle = null,
                size = Layout.cardSize,
                playCount = playlist.playCount,
                onPlay = onPlay?.let { callback -> { callback(playlist.id) } },
                onClick = { onOpen(playlist.id) },
                modifier = Modifier.staggeredAppearance(index = index, enabled = staggered),
            )
        }
    }
}
