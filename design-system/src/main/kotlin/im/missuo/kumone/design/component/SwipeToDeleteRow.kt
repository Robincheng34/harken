// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.design.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 统一的「左滑删除」曲目行容器（近期 / 最近播放 共用，视觉与交互完全一致）。
 *
 * 视觉：行级红色删除背景（[matchParentSize] 撑满整行 + 右侧垃圾桶图标），默认藏在内容层
 * 之下，向左滑才露出——与网易云「最近播放」页的删除条一致。内容层用 [contentBackground]
 * 不透明底色遮住红条（最近播放页传列表底色）。
 *
 * **透明内容模式**（[contentBackground] = null，用于「我的」页近期）：行不铺任何底色、
 * 与页面渐变完全无缝（此前铺固定红色在渐变页上会显出「一条一条」的色块）；红色删除条
 * 相应改为**右锚定、宽度随滑动距离生长**，只存在于滑出的缝隙里——露出观感与
 * 最近播放页一致（红条 + 垃圾桶），静止时完全隐形。
 *
 * 手势：[detectTapGestures] 点击播放、[detectHorizontalDragGestures] 左滑删除，二者分离，
 * 微位移不会误判为删除（规避 SwipeToDismissBox 的「点击即删」）。
 *
 * 动画：显式 [tween]（回弹 240ms / 提交 280ms），比默认 spring 慢、删除不再「一闪而过」。
 *
 * 删除确认（[guard] = true，来自设置「高级」项）：左滑露出红条后**停顿**（停留在
 * [REVEAL_DP] 处不自动删除），需「继续左滑过二级阈值 [DELETE_DP]」或「点击露出的红色区域」
 * 才真正删除；点击内容层则收回、不误删。
 */
@Composable
fun SwipeToDeleteRow(
    guard: Boolean,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    contentBackground: Color? = MaterialTheme.colorScheme.background,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val maxOffset = with(density) { 200.dp.toPx() }       // 最多左移（整条滑出）
    val revealThreshold = with(density) { 96.dp.toPx() }  // 露出红条阈值
    val deleteThreshold = with(density) { 168.dp.toPx() } // guard 模式二级删除阈值
    var revealed by remember(guard) { mutableStateOf(false) }

    val snapBack = tween<Float>(durationMillis = 240)
    val commit = tween<Float>(durationMillis = 280)

    Box(modifier = modifier.fillMaxWidth()) {
        // 红色删除背景：仅滑动后绘制，右侧垃圾桶图标。
        // - 不透明内容模式：整行撑满，靠内容层遮住未滑出区域。
        // - 透明内容模式：右锚定、宽度 = 滑出距离，只画在露出的缝隙里。
        if (offset.value < -1f) {
            val stripModifier = if (contentBackground != null) {
                Modifier.matchParentSize()
            } else {
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(with(density) { (-offset.value).toDp() })
                    .fillMaxHeight()
            }
            Box(
                modifier = stripModifier
                    .background(MaterialTheme.colorScheme.error)
                    // 仅 guard 停顿态下点击红区才删除（否则红区不可点，点击落到内容层）。
                    .clickable(enabled = guard && revealed) { onDelete() }
                    .padding(end = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.onError,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .then(
                    if (contentBackground != null) {
                        Modifier.background(contentBackground)
                    } else {
                        Modifier
                    },
                )
                .pointerInput(guard) {
                    detectTapGestures(onTap = {
                        if (guard && revealed) {
                            // 停顿态点击内容：收回，不误删、不误播。
                            scope.launch { offset.animateTo(0f, snapBack) }
                            revealed = false
                        } else {
                            onPlay()
                        }
                    })
                }
                .pointerInput(guard) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            val next = (offset.value + dragAmount).coerceIn(-maxOffset, 0f)
                            change.consume()
                            scope.launch { offset.snapTo(next) }
                        },
                        onDragEnd = {
                            scope.launch {
                                if (!guard) {
                                    if (offset.value <= -revealThreshold) {
                                        offset.animateTo(-maxOffset, commit)
                                        onDelete()
                                    } else {
                                        offset.animateTo(0f, snapBack)
                                    }
                                } else {
                                    when {
                                        offset.value <= -deleteThreshold -> {
                                            offset.animateTo(-maxOffset, commit)
                                            onDelete()
                                            revealed = false
                                        }
                                        offset.value <= -revealThreshold -> {
                                            // 停顿：停在露出位置，等用户继续滑或点红区。
                                            revealed = true
                                            offset.animateTo(-revealThreshold, snapBack)
                                        }
                                        else -> {
                                            revealed = false
                                            offset.animateTo(0f, snapBack)
                                        }
                                    }
                                }
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                offset.animateTo(0f, snapBack)
                                revealed = false
                            }
                        },
                    )
                },
        ) {
            content()
        }
    }
}
