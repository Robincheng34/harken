// Ported from kumone by missuo (LGPL-3.0)
//
// 详情页头部（T21 `components/DetailHeader.kt`）。
//
// 歌单 / 专辑 / 歌手共用：大封面 + 标题 + 副标题 + 播放量/曲目数 + 描述 + 收藏按钮。
// 对应 iOS 各详情页顶部的 `DetailHeader`。

package im.missuo.kumone.feature.detail.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import im.missuo.kumone.feature.detail.DetailHeaderState
import im.missuo.kumone.net.model.Formatters

/**
 * 详情页头部。
 *
 * @param onPlayAll 点击「播放全部」。
 * @param onToggleSubscribe 点击收藏按钮（歌单页有真实收藏态，专辑/歌手页仅展示）。
 */
@Composable
fun DetailHeader(
    header: DetailHeaderState,
    onPlayAll: () -> Unit,
    onToggleSubscribe: () -> Unit,
    modifier: Modifier = Modifier,
    showSubscribe: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = header.coverURL,
                contentDescription = header.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(140.dp)
                    .clip(MaterialTheme.shapes.extraLarge),
            )
            Spacer(modifier = Modifier.size(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = header.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (header.subtitle.isNotEmpty()) {
                    Text(
                        text = header.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = buildStats(header),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (showSubscribe) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = onToggleSubscribe) {
                        Icon(
                            imageVector = if (header.subscribed) {
                                Icons.Filled.Favorite
                            } else {
                                Icons.Filled.FavoriteBorder
                            },
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.size(4.dp))
                        Text(text = if (header.subscribed) "已收藏" else "收藏")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = onPlayAll, modifier = Modifier.weight(1f)) {
                Text(text = "播放全部")
            }
        }

        if (!header.description.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = header.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun buildStats(header: DetailHeaderState): String {
    val parts = mutableListOf<String>()
    if (header.playCount > 0) parts.add("播放 ${Formatters.playCount(header.playCount)}")
    if (header.trackCount > 0) parts.add("${header.trackCount} 首")
    if (header.subscribedCount > 0) parts.add("收藏 ${Formatters.playCount(header.subscribedCount)}")
    return parts.joinToString(" · ")
}
