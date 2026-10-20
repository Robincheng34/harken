// Ported from kumone by missuo (LGPL-3.0)
//
// 逐个入场动效（iOS `View.staggeredAppearance(index:id:)` 1:1 移植）。
//
// 每个子项按索引延迟 40ms（上限 400ms，见 `AppAnimations.staggerDelayMillis`）
// 淡入并轻微上移，全程只播放一次（iOS 以 id 去重，此处以 remember 达成）。

package im.missuo.kumone.design.component

import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import im.missuo.kumone.design.theme.AppAnimations
import im.missuo.kumone.design.theme.STANDARD_MILLIS

/** 入场位移距离（iOS 由 opacity + scale 组合，这里取淡入 + 上移近似）。 */
private val STAGGER_SLIDE_DISTANCE: Dp = 16.dp

/**
 * 逐个入场：首次组合时按 [index] 延迟淡入上移，只播放一次。
 *
 * @param index 子项索引（用于计算延迟）。
 * @param enabled 传 false 时直接以终态显示（如减少动效场景）。
 */
@Composable
fun Modifier.staggeredAppearance(
    index: Int,
    enabled: Boolean = true,
): Modifier {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    // enabled=false 的文档语义是「直接以终态显示」→ 恒为可见（不播动效）。
    // 原实现 target=0f：关闭动效的卡片反而 alpha=0 永久隐形——搜索结果
    // 「歌单」分区整片空白的根因（全应用只有它走了默认 staggered=false）。
    val target = if (!enabled || appeared) 1f else 0f
    val progress by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(
            durationMillis = STANDARD_MILLIS,
            delayMillis = AppAnimations.staggerDelayMillis(index).toInt(),
            easing = EaseOut,
        ),
        label = "staggeredAppearance",
    )
    return this.graphicsLayer {
        alpha = progress
        translationY = (1f - progress) * STAGGER_SLIDE_DISTANCE.toPx()
    }
}
