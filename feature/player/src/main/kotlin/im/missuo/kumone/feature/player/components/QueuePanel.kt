// Ported from kumone by missuo (LGPL-3.0)
//
// 播放队列面板（T27 `components/QueuePanel.kt`）——底部上拉的二级页面。
//
// 由宿主以 ModalBottomSheet 承载：从底部上拉，点击遮罩任意位置或下滑即可取消。
// 顶部提供「清空当前播放队列」；当前曲高亮、点击即播、队列为空展示空态。

package im.missuo.kumone.feature.player.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import im.missuo.kumone.net.model.Track

/**
 * 播放队列面板内容（作为底部二级页面内容，由宿主 ModalBottomSheet 承载）。
 *
 * @param tracks 队列（从当前曲之后开始，iOS `upcoming`）。
 * @param totalCount 队列总曲目数（含当前曲，iOS 头部 `N 首`）。
 * @param onPlayAt 点击队列中某项。
 * @param onClearQueue 清空当前播放队列（删除当前播放队列按钮）。
 */
@Composable
fun QueuePanel(
    tracks: List<Track>,
    totalCount: Int = tracks.size,
    onPlayAt: (Int) -> Unit,
    onClearQueue: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    Column(
        modifier = modifier
            .fillMaxWidth()
            // 上限 60% 屏高（上限而非固定值： ModalBottomSheet 实际可用高度受
            // 系统 inset 影响可能更小，固定高度会溢出导致列表尾部被裁）。
            .heightIn(max = (screenHeight * 0.6f).coerceAtMost(560.dp))
            .padding(vertical = 8.dp),
    ) {
        // 顶部：标题 + 曲目数 + 清空队列（iOS `Panels.swift:204-210` 头部形态）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "播放队列",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "$totalCount 首",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.5f),
            )
            Spacer(modifier = Modifier.weight(1f))
            // 清空当前播放队列
            Box(modifier = Modifier.clickable(onClick = onClearQueue).padding(8.dp)) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "清空队列",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        // 头部分隔线（iOS `Divider().opacity(0.3)`）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(Color.White.copy(alpha = 0.15f)),
        )
        Spacer(modifier = Modifier.height(4.dp))

        if (tracks.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "队列为空",
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.5f),
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                // 原 bug：key 用 t.id，队列含重复歌曲时抛 duplicate key 崩溃；
                // 改为 id+index 保证唯一。
                itemsIndexed(tracks, key = { index, t -> "${t.id}-$index" }) { index, track ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPlayAt(index) }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = track.name,
                            fontSize = 14.sp,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = track.artistNames,
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.55f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
