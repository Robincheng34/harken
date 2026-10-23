// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 播放页模糊封面全屏背景（官方客户端观感）。
//
// 官方播放页底层：当前专辑封面放大铺满全屏并重度模糊，再压纵向黑色渐变蒙版
// 保证白色文字可读。对应 iOS `NowPlayingView.backdrop` 的取色渐变方案在
// Android 侧的等价实现——直接模糊封面比取色更贴近官方客户端的「封面氛围」。
//
// API 分级：
// - API 31+：`Modifier.blur`（RenderEffect）真实高斯模糊；
// - API 26-30：无 RenderEffect，降级为「原图 + 重蒙版」，观感为暗化封面，
//   仍保证文字可读（minSdk=26，勿直接调用 31+ API）。

package com.rc.harken.feature.player.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.rc.harken.net.model.Formatters

/** 模糊半径（官方观感：封面细节完全化开，只剩色块氛围）。 */
private val BLUR_RADIUS = 90.dp

/** 封面加载完成后的淡入时长（与切歌时的背景过渡一致）。 */
private const val CROSSFADE_MILLIS = 600

/** 无封面时的底色（近黑，避免纯黑与蒙版之间出现死黑边界）。 */
private val FALLBACK_BASE = Color(0xFF1A1A1C)

/**
 * 模糊封面全屏背景。
 *
 * @param coverURL 当前封面 URL；为空时回退近黑底色。
 * @param modifier 修饰符。
 * @param content 背景之上叠加的内容（播放页主体）。
 */
@Composable
fun ArtworkBlurBackground(
    coverURL: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.fillMaxSize().background(FALLBACK_BASE)) {
        if (coverURL != null) {
            // 轻微放大 1.1x：模糊采样在图片边缘会向内收缩，放大后全屏无露边。
            val imageModifier = Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = 1.1f; scaleY = 1.1f }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(Formatters.resizedImageURL(coverURL, 768))
                        .crossfade(CROSSFADE_MILLIS)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.Center,
                    modifier = imageModifier.blur(BLUR_RADIUS, BlurredEdgeTreatment.Unbounded),
                )
            } else {
                // API 26-30 降级：不模糊，靠蒙版压暗。
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(Formatters.resizedImageURL(coverURL, 768))
                        .crossfade(CROSSFADE_MILLIS)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.Center,
                    modifier = imageModifier,
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f)),
                )
            }
        }

        // 纵向黑色渐变蒙版：顶部（顶栏）/底部（控制区与五图标行）重、中间轻，
        // 保证白字在任何封面上可读，同时中部保留封面氛围。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.38f),
                        0.35f to Color.Black.copy(alpha = 0.12f),
                        0.65f to Color.Black.copy(alpha = 0.22f),
                        1.0f to Color.Black.copy(alpha = 0.62f),
                    ),
                ),
        )
        content()
    }
}
