// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 发现页排行榜网格（T19 `components/ToplistGrid.kt`，iOS `ExploreView` `ToplistGrid` 1:1 移植）。
//
// 自适应列宽 300–420dp，行距 20dp；每行：110dp 封面（8dp 圆角）+ 名称（14sp semibold）
// + 更新频率（10.5sp 三级色）+ 前三名预览（"i. first - second" 11sp 二级色，间距 3dp）；
// 整行内边距 10dp、primary 0.04 底、12dp 大圆角。

package com.rc.harken.feature.explore.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.rc.harken.net.model.Formatters
import com.rc.harken.net.model.ToplistItem

/** 榜单封面尺寸（iOS 110）。 */
private val ToplistCoverSize = 110.dp

/**
 * 排行榜网格（iOS `ToplistGrid` 1:1 移植）。
 *
 * 原 bug：内部又是 [LazyVerticalGrid]，被 ExploreScreen 的外层 LazyVerticalGrid
 * 的 item 直接调用——同向嵌套滚动容器，toplists 非空时首帧抛
 * IllegalStateException(infinity height)。现改为非 Lazy 手动分块布局：
 * [BoxWithConstraints] 按可用宽度计算列数（最小列宽 300dp，与原
 * Adaptive(minSize = 300.dp) 一致），逐行 Row + weight 均分，保留同样的网格观感。
 *
 * 内边距说明：原实现自带 horizontal contentInset；现在被 ExploreScreen 外层
 * 网格（已有 contentInset）内联调用，去掉内部 padding 避免双重内缩。
 *
 * @param toplists 榜单列表。
 * @param onOpen 点击行（传入榜单 id）。
 */
@Composable
fun ToplistGrid(
    toplists: List<ToplistItem>,
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // 每行固定数量网格：按最小列宽 300dp 计算列数，至少 1 列。
        val columnCount = (maxWidth / 300.dp).toInt().coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            toplists.chunked(columnCount).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    rowItems.forEach { toplist ->
                        ToplistCell(
                            toplist = toplist,
                            onOpen = onOpen,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // 末行补齐空位，保证所有行列宽一致。
                    repeat(columnCount - rowItems.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** 单个榜单单元格：110dp 封面 + 名称 + 更新频率 + 前三名预览。 */
@Composable
private fun ToplistCell(
    toplist: ToplistItem,
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large) // iOS: Theme.Radius.large
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.04f))
            .clickable { onOpen(toplist.id) }
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AsyncImage(
            model = Formatters.resizedImageURL(toplist.coverImgUrl, 256),
            contentDescription = toplist.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(ToplistCoverSize)
                .clip(MaterialTheme.shapes.medium), // iOS: Theme.Radius.standard
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = toplist.name,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium, // iOS: 14pt semibold
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val frequency = toplist.updateFrequency
            if (!frequency.isNullOrEmpty()) {
                Text(
                    text = frequency,
                style = MaterialTheme.typography.bodySmall, // iOS: 10.5pt
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                toplist.tracks.take(3).forEachIndexed { index, preview ->
                    Text(
                        text = "${index + 1}. ${preview.first} - ${preview.second}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.width(0.dp))
    }
}
