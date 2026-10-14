// Ported from kumone by missuo (LGPL-3.0)
//
// 首页横滑 shelf 容器（T18 `components/HomeShelf.kt`，iOS `Cards.swift` `Shelf` 1:1 移植）。
//
// 结构：分区头（[SectionHeader]，左右缩进 contentInset）+ 横向 LazyRow（卡间距 16dp，
// 两侧边缘留白 max(0, contentInset - spacing) = 8dp，行高由调用方按卡片类型给定）。
// iOS 以 rowHeight 驱动 LazyHStack 懒加载，此处同样以 LazyRow 承载。
//
// 说明：架构文档 §3 T18 文件列表未单列本文件；为避免四个 shelf 组件各自重复同一
// 容器逻辑，抽为模块内 internal 组件（不跨模块暴露）。

package im.missuo.kumone.feature.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import im.missuo.kumone.design.component.SectionHeader
import im.missuo.kumone.design.theme.Layout

/**
 * 首页横滑 shelf（iOS `Shelf` 1:1 移植）。
 *
 * @param title 分区标题。
 * @param rowHeight 行高（封面 shelf 226dp / 歌手 shelf 196dp）。
 * @param content 横向卡片内容（[LazyListScope] 扩展）。
 */
@Composable
internal fun HomeShelf(
    title: String,
    rowHeight: Dp,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionHeader(
            title = title,
            modifier = Modifier.padding(horizontal = Layout.contentInset),
        )
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .height(rowHeight)
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 8.dp), // iOS edgeInset = contentInset - spacing
        ) {
            content()
        }
    }
}
