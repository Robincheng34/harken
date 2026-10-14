// Ported from kumone by missuo (LGPL-3.0)
//
// 排行榜横滑 shelf（共享组件，T18 起由 `:design-system` 提供，供首页/发现复用）。
//
// 160dp 封面 + 底部黑色渐变 + 更新频率文案（10sp medium 白 0.9，padding 8）；
// 下方榜单名一行。无播放量角标 / 无播放悬浮钮（与 iOS `toplistCard` 一致）。

package im.missuo.kumone.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import im.missuo.kumone.design.theme.Layout
import im.missuo.kumone.net.model.Formatters
import im.missuo.kumone.net.model.ToplistItem

/**
 * 排行榜横滑 shelf（iOS `toplistCard` 1:1 移植）。
 *
 * @param title 分区标题。
 * @param items 榜单列表。
 * @param onOpen 点击卡片（传入榜单 id，等价于歌单详情）。
 */
@Composable
fun ToplistShelf(
    title: String,
    items: List<ToplistItem>,
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionHeader(title = title)
    if (items.isEmpty()) return
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(items, key = { _, item -> item.id }) { _, item ->
            Column(
                modifier = Modifier
                    .width(Layout.cardSize)
                    .clickable { onOpen(item.id) },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(Layout.cardSize)
                        .clip(RoundedCornerShape(8.dp)), // iOS: Theme.Radius.standard
                ) {
                    AsyncImage(
                        model = Formatters.resizedImageURL(item.coverImgUrl, 384),
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // iOS: LinearGradient([.clear, .black 0.55], center → bottom)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                                ),
                            ),
                    )
                    val frequency = item.updateFrequency
                    if (!frequency.isNullOrEmpty()) {
                        Text(
                            text = frequency,
                            color = Color.White.copy(alpha = 0.9f),
                            fontWeight = FontWeight.Medium,
                            fontSize = 10.sp, // iOS: HomeView.swift L362（10pt medium）
                            maxLines = 1,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(8.dp),
                        )
                    }
                }
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(Layout.cardSize),
                )
            }
        }
    }
}
