// Ported from kumone by missuo (LGPL-3.0)
//
// 首页横滑 shelf 容器（T18 `components/HomeShelf.kt`，iOS `Cards.swift` `Shelf` 1:1 移植）。
//
// 结构：分区头（[SectionHeader]，沿用其自带的 16dp 水平内边距）+ 横向 LazyRow
// （卡间距 16dp，左缘 16dp、右缘 8dp）。行高由内容自适应——
// 原按卡种写死 226dp/196dp，而自适应卡宽下真实内容只有 ~180dp/~158dp，
// 差值变成 shelf 底部死白（用户反馈「推荐歌手底部空白太大」）。
//
// 左缘对齐：分区标题文字左缘与首张卡左缘必须落在同一条竖线（16dp）。
// 早先 SectionHeader 调用处又叠了一层 `padding(horizontal = contentInset)`，
// 标题被推到 40dp，而卡片还在 8dp/24dp，首页标题因此分成 16/40 两档；
// 现已去掉那层额外 padding，与 PlaylistShelf / ToplistShelf 的 16dp 基准一致。
//
// 说明：架构文档 §3 T18 文件列表未单列本文件；为避免四个 shelf 组件各自重复同一
// 容器逻辑，抽为模块内 internal 组件（不跨模块暴露）。

package im.missuo.kumone.feature.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import im.missuo.kumone.design.component.SectionHeader

/**
 * 首页横滑 shelf（iOS `Shelf` 1:1 移植）。
 *
 * @param title 分区标题。
 * @param content 横向卡片内容（[LazyListScope] 扩展）。
 */
@Composable
internal fun HomeShelf(
    title: String,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    // 分区头与其内容行之间不再叠加额外间距（原 Column 14dp + LazyRow 6dp，实际
    // 视觉间隔 32dp，而 PlaylistShelf / ToplistShelf 只有 12dp，各分区节奏不一致）。
    // 统一由 SectionHeader 自带的 12dp 上下内边距提供，使所有分区内部节奏一致。
    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        SectionHeader(title = title)
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            // 左缘与分区标题文字对齐（16dp，即 SectionHeader 自带的水平内边距），
            // 右侧保留 8dp 露出半张卡示意可右滑。
            contentPadding = PaddingValues(start = 16.dp, end = 8.dp),
        ) {
            content()
        }
    }
}
