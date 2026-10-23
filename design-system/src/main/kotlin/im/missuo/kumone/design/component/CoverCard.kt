// Ported from kumone by missuo (LGPL-3.0)
//
// 封面卡（iOS `Cards.swift` `CoverCard` / HomeView `CoverCardBody` 1:1 移植）。
//
// 方形封面 + 8dp 圆角（Theme.Radius.standard）+ primary 0.08 细边框；右上角播放量
// 角标（[playCount] > 0 时展示），左下角播放悬浮钮（传 [onPlay] 时展示）；下方
// 标题两行（13sp medium）+ 副标题一行（11sp）。封面图经 Coil 异步加载。

package im.missuo.kumone.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import im.missuo.kumone.design.theme.Layout
import im.missuo.kumone.net.model.Formatters

/**
 * 封面卡（iOS `CoverCard` 1:1 移植）。
 *
 * @param imageUrl 封面 URL（调用方经 `Formatters.resizedImageURL` 处理）。
 * @param contentDescription 无障碍描述。
 * @param title 主标题（两行截断）；传 null 不展示文字区。
 * @param subtitle 副标题（一行截断）。
 * @param size 固定卡宽（封面为 size×size）；传 null 时撑满可用宽度并保持 1:1。
 * @param playCount 播放量（> 0 时展示右上角角标）。
 * @param onPlay 播放悬浮钮回调；传 null 不展示。
 * @param onClick 整卡点击回调。
 */
@Composable
fun CoverCard(
    imageUrl: String?,
    contentDescription: String?,
    title: String? = null,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    size: Dp? = null,
    playCount: Int = 0,
    onPlay: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    /**
     * 是否为副标题预留固定一行（即使 subtitle 为空也占位）。
     *
     * 背景：卡片放在 `LazyRow` 里时，行高 = 当前可见卡片的最大高度。若同一行里有的卡片
     * 有 copywriter、有的没有（高一行），横向滑动使新卡片进入组合时行高就会变，
     * 父级 `LazyColumn` 中它下方的分区会整体跳动（用户反馈「滑动推荐歌单时下面的
     * 雷达/排行榜跳动」）。置 true 让每个卡片高度一致，行高恒定。
     */
    reserveSubtitleLine: Boolean = false,
) {
    Column(
        modifier = modifier.then(
            if (size != null) Modifier.size(width = size, height = Dp.Unspecified) else Modifier,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CoverArtwork(
            imageUrl = imageUrl,
            contentDescription = contentDescription,
            size = size,
            playCount = playCount,
            onPlay = onPlay,
            onClick = onClick,
        )
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                // minLines = 2：单行标题也占两行，保证同一行卡片等高
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = if (size != null) Modifier.size(width = size, height = Dp.Unspecified) else Modifier,
            )
        }
        if (!subtitle.isNullOrEmpty() || reserveSubtitleLine) {
            Text(
                text = subtitle.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                minLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (size != null) Modifier.size(width = size, height = Dp.Unspecified) else Modifier,
            )
        }
    }
}

/** 封面图 + 角标 + 播放悬浮钮（iOS `CoverCard` 的 ZStack 部分）。 */
@Composable
private fun CoverArtwork(
    imageUrl: String?,
    contentDescription: String?,
    size: Dp?,
    playCount: Int,
    onPlay: (() -> Unit)?,
    onClick: (() -> Unit)?,
) {
    val shape = RoundedCornerShape(8.dp) // iOS: Theme.Radius.standard
    Box(
        modifier = Modifier
            .then(if (size != null) Modifier.size(size) else Modifier.fillMaxWidth().aspectRatio(1f))
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .matchParentSize()
                .clip(shape),
            placeholder = androidx.compose.ui.graphics.painter.ColorPainter(
                MaterialTheme.colorScheme.surfaceVariant,
            ),
        )
        // iOS: strokeBorder(.primary.opacity(0.08), lineWidth: 0.5)
        Box(
            modifier = Modifier
                .matchParentSize()
                .border(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), shape),
        )

        if (playCount > 0) {
            PlayCountBadge(
                count = playCount,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
            )
        }

        if (onPlay != null) {
            PlayOverlayButton(
                onPlay = onPlay,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp),
            )
        }
    }
}

/** 播放量角标（iOS `PlayCountBadge`）：半透明黑底 + 播放图标 + 万/亿计数。 */
@Composable
fun PlayCountBadge(
    count: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        Text(
            text = "▶ ${Formatters.playCount(count)}",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.92f),
        )
    }
}

/** 播放悬浮钮（iOS `PlayOverlayButton`）：左下角半透明圆形播放键。 */
@Composable
fun PlayOverlayButton(
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.42f))
            .clickable(onClick = onPlay),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** 默认卡尺寸（iOS `Theme.Layout.cardSize`）。 */
val CoverCardSize: Dp = Layout.cardSize
