// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 新碟上架横滑 shelf（T18 `components/NewAlbumShelf.kt`，iOS `HomeView` 新碟上架 1:1 移植）。
//
// 卡宽按可用宽度自适应（iOS `Theme.Layout.fittedShelfCardSize`）；副标题为歌手名；
// 播放悬浮钮（[onPlay] 非空时）直接播放整张专辑。

package com.rc.harken.feature.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rc.harken.design.component.CoverCard
import com.rc.harken.design.component.SectionHeader
import com.rc.harken.design.theme.Layout
import com.rc.harken.feature.home.R
import com.rc.harken.net.model.AlbumSummary
import com.rc.harken.net.model.Formatters

/**
 * 新碟上架横滑 shelf。
 *
 * @param items 专辑列表。
 * @param onOpen 点击卡片（传入专辑 id）。
 * @param onPlay 播放悬浮钮（传入专辑 id）；传 null 不展示悬浮钮。
 */
@Composable
fun NewAlbumShelf(
    items: List<AlbumSummary>,
    onOpen: (Long) -> Unit,
    onPlay: ((Long) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // 与 HomeShelf 保持一致：分区头与内容行之间不再叠加额外间距（原 Column 14dp +
    // LazyRow 6dp），统一由 SectionHeader 自带的 12dp 下内边距提供。
    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        // 左缘与 HomeShelf 统一：不再给 SectionHeader 额外叠 contentInset（那会让
        // 标题文字落在 40dp、与其他分区的 16dp 分成两档），标题沿用 SectionHeader
        // 自带的 16dp 水平内边距，与首张卡左缘对齐到同一条竖线。
        SectionHeader(title = stringResource(R.string.home_new_albums), showChevron = true)
        // 卡宽与其他分区（推荐歌单/排行榜 160dp）一致:自适应卡宽在 411dp 屏上
        // 只给 ~113dp(3-4 张/行),与相邻分区的 2 张/行节奏冲突,用户反馈
        // 「新碟上架和其他分区卡片大小不一、空白别扭」。
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            // 同 HomeShelf:左缘 16dp 与分区标题文字对齐,右侧 8dp 透视。
            contentPadding = PaddingValues(start = 16.dp, end = 8.dp),
        ) {
            itemsIndexed(items, key = { _, album -> album.id }) { _, album ->
                CoverCard(
                    imageUrl = Formatters.resizedImageURL(album.picUrl, 384),
                    contentDescription = album.name,
                    title = album.name,
                    subtitle = album.artistName,
                    // artistName 可为空:空时不渲染副标题行会让同行卡片高低不一
                    // (LazyRow 以可见项最大高度定行高,横滑进新卡行高变化 →
                    // 首页下方分区跳动,e24fc43 同因)。占住副标题行保证等高。
                    reserveSubtitleLine = true,
                    size = Layout.cardSize,
                    onPlay = onPlay?.let { callback -> { callback(album.id) } },
                    onClick = { onOpen(album.id) },
                )
            }
        }
    }
}
