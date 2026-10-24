// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 首页分类宫格(2×4,官方音乐首页同款模块:白色圆角小卡 + 图标 + 分类名)。
// 点击统一跳发现页(其顶部分类胶囊可继续筛选);不接入官方分类数据,
// 全部为本项目真实存在的导航目的地。

package com.rc.harken.feature.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rc.harken.design.navigation.Destinations

/** 分类宫格项:分类名 + 图标 + 目的地路由。 */
private data class HomeCategory(
    val label: String,
    val icon: ImageVector,
    val route: String,
)

/** 首页分类宫格(官方音乐首页同款 2×4)。 */
@Composable
internal fun CategoryGridSection(
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val explore = Destinations.EXPLORE
    val categories = listOf(
        HomeCategory("排行榜", Icons.Filled.Leaderboard, explore),
        HomeCategory("歌单", Icons.AutoMirrored.Filled.QueueMusic, explore),
        HomeCategory("华语", Icons.Filled.MusicNote, explore),
        HomeCategory("情歌", Icons.Filled.Favorite, explore),
        HomeCategory("说唱", Icons.Filled.Mic, explore),
        HomeCategory("原创", Icons.Filled.MusicNote, explore),
        HomeCategory("VIP", Icons.Filled.WorkspacePremium, explore),
        HomeCategory("欧美", Icons.Filled.Public, explore),
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        categories.chunked(4).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowItems.forEach { item ->
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .background(
                                MaterialTheme.colorScheme.surface,
                                RoundedCornerShape(12.dp),
                            )
                            .clickable { onNavigate(item.route) }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = item.label,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                    }
                }
                // 补位空槽(分类数不是 4 的倍数时保持等宽)。
                repeat(4 - rowItems.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}
