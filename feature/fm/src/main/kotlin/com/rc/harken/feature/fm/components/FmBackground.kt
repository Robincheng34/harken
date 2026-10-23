// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 漫游背景（T23 `components/FmBackground.kt`）。
//
// 1:1 复刻 iOS `FMView.backdrop`：底色 + 当前封面 384px 放大模糊（radius 80、
// 饱和度提升、深色 0.45 / 浅色 0.30 不透明）+ 自上而下渐变蒙版。

package com.rc.harken.feature.fm.components

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.rc.harken.design.theme.AppAnimations
import com.rc.harken.net.model.Formatters

/** iOS: `.blur(radius: 80)`（Compose `blur` 以 dp 表达，同数量级）。 */
private val BackdropBlur = 80.dp

/** iOS: `.saturation(1.4)`。 */
private val BackdropSaturation = 1.4f

/**
 * 漫游页沉浸背景。
 *
 * @param coverURL 当前曲封面（内部按 384px 请求）；为空时仅底色。
 */
@Composable
fun FmBackground(
    coverURL: String?,
    modifier: Modifier = Modifier,
) {
    val dark = isSystemInDarkTheme()
    val baseColor = MaterialTheme.colorScheme.background
    // iOS: `.animation(AppAnimation.smooth, value: track?.id)` —— 换曲时平滑淡入。
    val imageAlpha by animateFloatAsState(
        targetValue = if (coverURL != null) (if (dark) 0.45f else 0.30f) else 0f,
        animationSpec = AppAnimations.smooth(),
        label = "fmBackdropAlpha",
    )

    Box(modifier = modifier.fillMaxSize().background(baseColor)) {
        if (coverURL != null) {
            val imageModifier = Modifier
                .fillMaxSize()
                .alpha(imageAlpha)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // API 31+：真实高斯模糊（RenderEffect）。
                AsyncImage(
                    model = Formatters.resizedImageURL(coverURL, 384),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = ColorFilter.colorMatrix(
                        androidx.compose.ui.graphics.ColorMatrix().apply {
                            setToSaturation(BackdropSaturation)
                        },
                    ),
                    modifier = imageModifier.blur(radius = BackdropBlur),
                )
            } else {
                // 原 bug：Modifier.blur 无 SDK 分级，API<31（无 RenderEffect）
                // 模糊直接无效。退化为不模糊 + 加深蒙版压暗（对齐同工程
                // ArtworkBlurBackground 的分级写法），保证观感与文字可读。
                AsyncImage(
                    model = Formatters.resizedImageURL(coverURL, 384),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = ColorFilter.colorMatrix(
                        androidx.compose.ui.graphics.ColorMatrix().apply {
                            setToSaturation(BackdropSaturation)
                        },
                    ),
                    modifier = imageModifier,
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = if (dark) 0.35f else 0.22f)),
                )
            }
        }
        // iOS: LinearGradient(.clear → background.opacity(0.6), top→bottom)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, baseColor.copy(alpha = 0.6f)),
                    ),
                ),
        )
    }
}
