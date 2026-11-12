// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 搜索落地页（官方搜索页「热搜榜 + 搜索历史」，`components/SearchLanding.kt`）。
//
// 1:1 复刻官方 Android 客户端搜索落地页：
//  - 热搜榜：排名数字（前三名红/橙/琥珀，其余灰）+ 词条 + 官方角标
//    （`iconType` 1=热 2=新 3=沸 4=爆，优先 `iconUrl` 定制图）+ 热度值；
//  - 搜索历史：标题 + 垃圾桶清空；历史词为流式圆角胶囊（点击直接搜索）。
//
// 数据来自 `SearchUiState.hotSearches / history`（ViewModel 侧由
// `NeteaseApi.hotSearchDetail()` 与 `SearchHistoryRepository` 供给）。

package com.rc.harken.feature.search.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.rc.harken.design.theme.Layout
import com.rc.harken.feature.search.R
import com.rc.harken.feature.search.SearchUiState
import com.rc.harken.net.model.HotSearchItem
import kotlin.math.roundToInt

/**
 * 热搜角标 / 排名色（官方榜位色，非主题 token，刻意保留以保持与网易云官方一致）。
 */
private object HotSearchColors {
    val Hot = Color(0xFFE03C3C) // 热
    val New = Color(0xFF2E7DF6) // 新
    val Boil = Color(0xFFE8801A) // 沸
    val Boom = Color(0xFFC20C0C) // 爆
    val Rank1 = Color(0xFFD03030)
    val Rank2 = Color(0xFFE8801A)
    val Rank3 = Color(0xFFF5A623)
    val RankOther = Color(0xFF9E9E9E)
}

/** 排名颜色：1 红 / 2 橙 / 3 琥珀 / 其余灰（官方热搜榜，非主题 token）。 */
private fun rankColor(rank: Int): Color = when (rank) {
    1 -> HotSearchColors.Rank1
    2 -> HotSearchColors.Rank2
    3 -> HotSearchColors.Rank3
    else -> HotSearchColors.RankOther
}

/**
 * 搜索落地页：热搜榜 + 搜索历史（官方）。
 *
 * @param onHotItemClick 点击热搜词条 → 以该词提交搜索。
 * @param onHistoryItemClick 点击历史词条 → 以该词提交搜索。
 * @param onClearHistory 清空搜索历史（标题栏垃圾桶）。
 * @param bottomClearance 列表尾部净空（迷你播放条 + TabBar 悬浮 chrome 占位）。
 */
@Composable
fun SearchLandingPage(
    uiState: SearchUiState,
    onHotItemClick: (String) -> Unit,
    onHistoryItemClick: (String) -> Unit,
    onClearHistory: () -> Unit,
    onQuickEntryClick: (SearchQuickEntry) -> Unit,
    modifier: Modifier = Modifier,
    bottomClearance: Dp = 24.dp,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomClearance),
    ) {
        item(key = "quick") {
            QuickEntryRow(onEntryClick = onQuickEntryClick)
        }
        item(key = "hot") {
            HotSearchBoard(
                items = uiState.hotSearches,
                isLoading = uiState.isLoadingHot,
                onItemClick = onHotItemClick,
            )
        }
        if (uiState.history.isNotEmpty()) {
            item(key = "history") {
                SearchHistoryBoard(
                    history = uiState.history,
                    onItemClick = onHistoryItemClick,
                    onClear = onClearHistory,
                )
            }
        }
    }
}

/** 热搜榜（官方：排名 + 词条 + 角标 + 热度值）。 */
@Composable
private fun HotSearchBoard(
    items: List<HotSearchItem>,
    isLoading: Boolean,
    onItemClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionTitle(text = stringResource(R.string.search_hot_title))

        when {
            isLoading && items.isEmpty() -> Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Layout.contentInset, vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.search_hot_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            items.isEmpty() -> Text(
                text = stringResource(R.string.search_hot_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Layout.contentInset, vertical = 12.dp),
            )

            else -> items.take(HotLimit).forEachIndexed { index, item ->
                HotSearchRow(
                    rank = index + 1,
                    item = item,
                    onClick = { onItemClick(item.searchWord) },
                )
            }
        }
    }
}

/** 热搜榜单行：排名数字 + 词条 + 角标 + 右侧热度值。 */
@Composable
private fun HotSearchRow(
    rank: Int,
    item: HotSearchItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                start = Layout.contentInset,
                end = Layout.contentInset,
                top = 10.dp,
                bottom = 10.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = rank.toString(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = rankColor(rank),
            textAlign = TextAlign.Center,
            modifier = Modifier.width(28.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = item.searchWord,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(6.dp))
        HotSearchBadge(iconType = item.iconType, iconUrl = item.iconUrl)
        Spacer(modifier = Modifier.weight(1f))
        item.score?.let { score ->
            Text(
                text = formatHotScore(score),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                textAlign = TextAlign.End,
                modifier = Modifier.width(56.dp),
            )
        }
    }
}

/**
 * 热搜角标：官方 `iconUrl` 定制图优先，否则按 `iconType` 渲染文字胶囊
 * （1=热 2=新 3=沸 4=爆）。
 */
@Composable
private fun HotSearchBadge(
    iconType: Int?,
    iconUrl: String?,
    modifier: Modifier = Modifier,
) {
    if (!iconUrl.isNullOrBlank()) {
        // 官方定制角标图：必须同时约束宽高——热搜推广条目的角标是宽幅 banner 图
        // （如「用你的好品味赚点钱」），只给 height 时 Coil 按原始宽高比测量出
        // 数百 dp 宽，把词条 Text(weight 1f) 挤成 0 宽、score 推出行外，
        // 整行看起来就是空白（热搜榜第 3 条空白的真正根因）。
        AsyncImage(
            model = iconUrl,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier.size(14.dp),
        )
        return
    }
    val (label, color) = when (iconType) {
        1 -> stringResource(R.string.search_hot_badge_hot) to HotSearchColors.Hot
        2 -> stringResource(R.string.search_hot_badge_new) to HotSearchColors.New
        3 -> stringResource(R.string.search_hot_badge_boil) to HotSearchColors.Boil
        4 -> stringResource(R.string.search_hot_badge_boom) to HotSearchColors.Boom
        else -> return
    }
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(color)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            maxLines = 1,
        )
    }
}

/** 搜索历史：标题 + 垃圾桶清空 + 流式胶囊词条。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchHistoryBoard(
    history: List<String>,
    onItemClick: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Layout.contentInset, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.search_history_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onClear) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.search_history_clear),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Layout.contentInset, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            history.forEach { keyword ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .clickable { onItemClick(keyword) }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    Text(
                        text = keyword,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 分区标题（官方：粗体大标题，如「热搜榜」「搜索历史」）。 */
@Composable
internal fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Layout.contentInset, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** 热搜榜展示条数（官方约 20 条）。 */
private const val HotLimit = 20

/** 热度值格式化：>= 1 万显示「x.x万」，否则原值（官方右侧灰色热度）。 */
internal fun formatHotScore(score: Int): String =
    if (score >= 10_000) {
        val rounded = (score / 10_000.0 * 10).roundToInt() / 10.0
        if (rounded % 1.0 == 0.0) "${rounded.toInt()}万" else "${rounded}万"
    } else {
        score.toString()
    }

/** 搜索框下方快捷入口（对标官方：歌手 / 曲风）。 */
enum class SearchQuickEntry { ARTIST, GENRE }

/** 快捷入口行：两项图标 + 文字，等宽横排（官方搜索框下方）。 */
@Composable
private fun QuickEntryRow(
    onEntryClick: (SearchQuickEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Layout.contentInset, vertical = 12.dp),
    ) {
        QuickEntryItem(
            icon = Icons.Filled.Person,
            label = stringResource(R.string.search_quick_artist),
            onClick = { onEntryClick(SearchQuickEntry.ARTIST) },
            modifier = Modifier.weight(1f),
        )
        QuickEntryItem(
            icon = Icons.Filled.Category,
            label = stringResource(R.string.search_quick_genre),
            onClick = { onEntryClick(SearchQuickEntry.GENRE) },
            modifier = Modifier.weight(1f),
        )
    }
}

/** 快捷入口单项：圆形图标底 + 文字（官方风格）。 */
@Composable
private fun QuickEntryItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}
