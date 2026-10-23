// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 搜索结果分段控件（P1-1 `components/SearchSegmented.kt`）。
//
// 1:1 复刻 iOS `SearchView` 的 `Picker(.pickerStyle(.segmented))`：五段
// 综合 / 单曲 / 歌手 / 专辑 / 歌单，选中段为白字 + 品牌红底，未选中为透明底 +
// 次要文字；左右留白 `Theme.Layout.contentInset`（= `Layout.contentInset`）。

package com.rc.harken.feature.search.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rc.harken.design.theme.Layout
import com.rc.harken.feature.search.R
import com.rc.harken.feature.search.SearchTab

/** 分段控件高度（贴合 iOS `.segmented` 的紧凑感）。 */
private val SegmentedHeight = 32.dp

/** 段间距（iOS 分段控件相邻段之间约 2pt）。 */
private val SegmentSpacing = 2.dp

/**
 * 搜索结果分段切换（综合 / 单曲 / 歌手 / 专辑 / 歌单）。
 *
 * @param selected 当前分段。
 * @param onSelect 选中变更回调（由 [SearchViewModel.selectTab] 处理惰性加载）。
 */
@Composable
fun SearchSegmented(
    selected: SearchTab,
    onSelect: (SearchTab) -> Unit,
    modifier: Modifier = Modifier,
    tabs: List<SearchTab> = SearchTab.entries,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Layout.contentInset)
            .height(SegmentedHeight)
            .clip(RoundedCornerShape(8.dp))
            // iOS 分段控件底板：次要表面色。
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SegmentSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                val isSelected = tab == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .height(SegmentedHeight)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            // 选中段：品牌红实底（iOS 选中段为高对比填充）。
                            if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.Transparent
                            },
                        )
                        .clickable { onSelect(tab) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(tab.labelRes()),
                        fontSize = 13.sp,
                        color = if (isSelected) {
                            Color.White
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Tab 的中文标签资源（iOS `Tab.rawValue`；文案真源见 `strings.xml`）。 */
internal fun SearchTab.labelRes(): Int = when (this) {
    SearchTab.ALL -> R.string.search_tab_all
    SearchTab.SONGS -> R.string.search_tab_songs
    SearchTab.ARTISTS -> R.string.search_tab_artists
    SearchTab.ALBUMS -> R.string.search_tab_albums
    SearchTab.PLAYLISTS -> R.string.search_tab_playlists
}