// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 发现页分类 chips（T19 `components/CategoryChips.kt`，iOS `ExploreView.categoryChips`
// + `ButtonStyles.ChipButtonStyle` 1:1 移植）。
//
// 横向滚动，卡间距 8dp，两侧边缘留白 max(0, contentInset - 8) = 16dp；胶囊样式：
// 选中 = 品牌红底白字（12sp semibold），未选 = primary 0.06 底 primary 字（12sp medium），
// 按压缩放 0.95。

package com.rc.harken.feature.explore.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rc.harken.design.theme.AppAnimations
import com.rc.harken.design.component.staggeredAppearance
import com.rc.harken.design.theme.Layout
import com.rc.harken.feature.explore.R

/**
 * 分类 chips 横滑行。
 *
 * @param categories 分类列表（与 API 分类参数同值）。
 * @param selected 当前选中分类。
 * @param onSelect 点击分类回调。
 */
@Composable
fun CategoryChips(
    categories: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (categories.isEmpty()) return
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // iOS 边缘留白：max(0, contentInset - spacing)。
        Spacer(Modifier.width(Layout.contentInset - 10.dp))
        categories.forEachIndexed { index, category ->
            CategoryChip(
                label = stringResource(categoryLabelRes(category)),
                selected = category == selected,
                onClick = { onSelect(category) },
                // 入场逐个延迟浮现（stagger 令牌：40ms/个，上限 400ms）。
                modifier = Modifier.staggeredAppearance(index = index % 10),
            )
        }
        Spacer(Modifier.width(Layout.contentInset - 10.dp))
    }
}

/** 单个分类 chip（iOS `ChipButtonStyle` 1:1 移植；按压缩放无过冲，全局动效统一）。 */
@Composable
private fun CategoryChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f, // iOS: pressScale 0.95
        animationSpec = AppAnimations.spring(),
        label = "chipPressScale",
    )
    Row(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(percent = 50)) // iOS: Capsule()
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else MaterialTheme.colorScheme.primary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

/** 分类名 → 中文文案资源（iOS `LocalizedStringKey(category)` 等价物）。 */
private fun categoryLabelRes(category: String): Int = when (category) {
    "全部" -> R.string.explore_category_all
    "推荐歌单" -> R.string.explore_category_recommended
    "精品歌单" -> R.string.explore_category_high_quality
    "排行榜" -> R.string.explore_category_toplists
    "官方" -> R.string.explore_category_official
    "华语" -> R.string.explore_category_chinese
    "流行" -> R.string.explore_category_pop
    "摇滚" -> R.string.explore_category_rock
    "民谣" -> R.string.explore_category_folk
    "电子" -> R.string.explore_category_electronic
    "轻音乐" -> R.string.explore_category_light
    "说唱" -> R.string.explore_category_rap
    "爵士" -> R.string.explore_category_jazz
    "古典" -> R.string.explore_category_classical
    "影视原声" -> R.string.explore_category_soundtrack
    "ACG" -> R.string.explore_category_acg
    "古风" -> R.string.explore_category_ancient_style
    "怀旧" -> R.string.explore_category_retro
    "治愈" -> R.string.explore_category_healing
    "放松" -> R.string.explore_category_relax
    "伤感" -> R.string.explore_category_sad
    "快乐" -> R.string.explore_category_happy
    "学习" -> R.string.explore_category_study
    "工作" -> R.string.explore_category_work
    "运动" -> R.string.explore_category_exercise
    "驾车" -> R.string.explore_category_driving
    "夜晚" -> R.string.explore_category_night
    else -> R.string.explore_category_all
}
