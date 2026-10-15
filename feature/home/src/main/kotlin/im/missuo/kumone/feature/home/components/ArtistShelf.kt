// Ported from kumone by missuo (LGPL-3.0)
//
// 推荐歌手横滑 shelf（T18 `components/ArtistShelf.kt`，iOS `Cards.swift` `ArtistCard`
// 与 HomeView 推荐歌手 Shelf 1:1 移植）。
//
// 128dp 圆形头像（256 缩放）+ primary 0.08 细描边；名字 13sp medium 一行；
// 整卡宽 140dp；行高由 [HomeShelf] 依内容自适应（不再写死 196dp）。

package im.missuo.kumone.feature.home.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import im.missuo.kumone.feature.home.R
import im.missuo.kumone.net.model.ArtistSummary
import im.missuo.kumone.net.model.Formatters

/** 歌手卡整宽（iOS `size + 12` = 140）。 */
private val ArtistCardWidth = 140.dp

/** 圆形头像尺寸（iOS `ArtistCard` 128）。 */
private val ArtistAvatarSize = 128.dp

/**
 * 推荐歌手横滑 shelf。
 *
 * @param items 歌手列表。
 * @param onOpen 点击卡片（传入歌手 id）。
 */
@Composable
fun ArtistShelf(
    items: List<ArtistSummary>,
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    HomeShelf(
        title = stringResource(R.string.home_top_artists),
        modifier = modifier,
    ) {
        itemsIndexed(items, key = { _, artist -> artist.id }) { _, artist ->
            Column(
                modifier = Modifier
                    .width(ArtistCardWidth)
                    .clickable { onOpen(artist.id) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(ArtistAvatarSize)
                        .clip(CircleShape)
                        // iOS: Circle().strokeBorder(.primary.opacity(0.08), lineWidth: 0.5)
                        .border(
                            0.5.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                            CircleShape,
                        ),
                ) {
                    AsyncImage(
                        model = Formatters.resizedImageURL(artist.picUrl, 256),
                        contentDescription = artist.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(ArtistAvatarSize),
                )
            }
        }
    }
}
