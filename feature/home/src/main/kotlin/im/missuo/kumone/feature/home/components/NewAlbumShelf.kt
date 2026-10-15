// Ported from kumone by missuo (LGPL-3.0)
//
// 新碟上架横滑 shelf（T18 `components/NewAlbumShelf.kt`，iOS `HomeView` 新碟上架 1:1 移植）。
//
// 卡宽按可用宽度自适应（iOS `Theme.Layout.fittedShelfCardSize`）；副标题为歌手名；
// 播放悬浮钮（[onPlay] 非空时）直接播放整张专辑。

package im.missuo.kumone.feature.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import im.missuo.kumone.design.component.CoverCard
import im.missuo.kumone.design.component.SectionHeader
import im.missuo.kumone.design.theme.Layout
import im.missuo.kumone.feature.home.R
import im.missuo.kumone.net.model.AlbumSummary
import im.missuo.kumone.net.model.Formatters

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
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionHeader(
            title = stringResource(R.string.home_new_albums),
            modifier = Modifier.padding(horizontal = Layout.contentInset),
        )
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
        ) {
            // iOS: fittedShelfCardSize(availableWidth: geometry.size.width, leadingInset: contentInset)
            val cardSize: Dp = Layout.fittedShelfCardSize(maxWidth, Layout.contentInset)
            // 不再固定 coverShelfHeight（226dp）：自适应卡宽算出的内容高度随屏宽变化
            // （1440px 屏上卡片 ~113dp，内容总高 ~180dp），固定行高会在 shelf 底部留出
            // ~46dp 空档，叠加分区间距后就是用户看到的「上下间隔太大」。让 LazyRow
            // 按内容自撑高度，卡片顶对齐，底部不再有多余空隙。
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                itemsIndexed(items, key = { _, album -> album.id }) { _, album ->
                    CoverCard(
                        imageUrl = Formatters.resizedImageURL(album.picUrl, 384),
                        contentDescription = album.name,
                        title = album.name,
                        subtitle = album.artistName,
                        size = cardSize,
                        onPlay = onPlay?.let { callback -> { callback(album.id) } },
                        onClick = { onOpen(album.id) },
                    )
                }
            }
        }
    }
}
