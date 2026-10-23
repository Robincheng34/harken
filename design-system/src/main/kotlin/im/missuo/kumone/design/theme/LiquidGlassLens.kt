// 液态玻璃「选中标尺」（selector lens）— 酷安 / IT之家 底部导航栏镜片风格移植。
//
// 折射方案（2026-10-23 重做，适配 Compose 1.7.x）：
// Compose 1.7.x 的 androidx.compose.ui.graphics.RenderEffect 仅暴露 BlurEffect /
// OffsetEffect 等内置子类，未提供 RuntimeShader（AGSL）折射的公开封装，且其构造器为
// private、不可在模块外继承。因此本实现走「实时毛玻璃」路线，定位与酷安「迪兰」同款
// frosted glass：
//
//  - 折射源直接是 Compose 的 live GraphicsLayer（lensLayer，由 KumoneApp.captureModifier
//    以 GPU record 持续录制底栏背后内容，零回读、无滞后）。
//  - 镜片经 BlurEffect 在 GPU 上实时模糊该 live 源；镜片随选中 Tab 连续滑动
//    （translate(-lensX) 平移采样窗口），每帧与本帧合成同源——
//    彻底消除旧实现 toImageBitmap 15fps 回读导致的「烙旧帧 / 滑动卡顿」。
//  - 玻璃观感（品牌 tint + 顶部高光 + 发丝描边 + 按压光学轮廓）由上层 crisp 叠层绘制，
//    不随模糊一起糊化，保证液态玻璃的「清亮边缘」。
//
// 安全约束：BlurEffect 仅 API 33+ 可用，所有相关调用被 SDK_INT>=33 守卫。
// API<33 或无背后层时，回落到纯 Canvas 绘制的半透明圆角药丸，保证零回归。

package im.missuo.kumone.design.theme

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 液态玻璃选中标尺。
 *
 * @param backdrop 底栏背后内容的 live [GraphicsLayer]（折射源，由 KumoneApp 的
 *   captureModifier 持续录制，与本帧合成同源，无回读、无滞后）。为 null 时回落 Canvas 兜底。
 * @param lensX 镜片在底栏本地坐标系中的 X 位置（px），每帧读取；镜片滑动时逐帧变化。
 *   折射源绘制时按 -lensX 平移，使镜片采样窗口实时跟手（等价 IT之家 offset uniform）。
 * @param tint 品牌色（选中态 primary）。
 * @param edgeHighlight 边缘光学轮廓强度：按压 1f，其余 0f。
 * @param cornerRadiusDp 镜片圆角。
 * @param frostRadiusDp 毛玻璃模糊半径。
 */
@Composable
fun LiquidGlassLens(
    backdrop: GraphicsLayer?,
    tint: Color,
    edgeHighlight: Float,
    lensX: () -> Float,
    modifier: Modifier = Modifier,
    cornerRadiusDp: Dp = 30.dp,
    frostRadiusDp: Dp = 18.dp,
) {
    val density = LocalDensity.current.density
    val cornerRadiusPx = cornerRadiusDp.value * density
    val frostRadiusPx = frostRadiusDp.value * density

    if (Build.VERSION.SDK_INT >= 33 && backdrop != null) {
        // 下层：实时毛玻璃——把 live backdrop 按 -lensX 平移采到镜片窗口，
        // 经 BlurEffect 在 GPU 上模糊（与背后内容同源、每帧重录，无回读无滞后）。
        // 修饰符顺序关键：clip 在外、graphicsLayer(BlurEffect) 在内——模糊结果先产出、
        // 再被外层裁到圆角矩形，模糊不会溢出镜片边界（反过来写会把模糊光晕烙到镜片外）。
        val blur = remember(frostRadiusPx) {
            BlurEffect(frostRadiusPx, frostRadiusPx, TileMode.Clamp)
        }
        Box(
            modifier = modifier.then(
                Modifier
                    .clip(RoundedCornerShape(cornerRadiusDp))
                    .graphicsLayer { renderEffect = blur }
                    .drawWithContent {
                        translate(-lensX()) { drawLayer(backdrop) }
                    },
            ),
        )
        // 上层：crisp 玻璃叠层（不模糊）——品牌 tint + 顶部高光 + 底部厚度暗影 +
        // 发丝描边 + 按压光学轮廓，定位 IT之家 / 酷安同款液态玻璃观感。
        Box(
            modifier = modifier.then(
                Modifier.drawWithContent {
                    val px = this.size
                    val r = CornerRadius(cornerRadiusPx)
                    // 品牌 tint：静止淡、按压深。
                    drawRoundRect(
                        color = tint.copy(alpha = 0.14f + 0.12f * edgeHighlight),
                        topLeft = Offset.Zero,
                        size = px,
                        cornerRadius = r,
                    )
                    // 顶部高光（玻璃反光的「上亮」），越靠上越亮。
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.30f),
                                Color.White.copy(alpha = 0.0f),
                            ),
                            startY = 0f,
                            endY = px.height * 0.55f,
                        ),
                        topLeft = Offset.Zero,
                        size = px,
                        cornerRadius = r,
                    )
                    // 底部一层极淡暗影，制造厚度。
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.0f),
                                Color.Black.copy(alpha = 0.10f),
                            ),
                            startY = px.height * 0.5f,
                            endY = px.height,
                        ),
                        topLeft = Offset.Zero,
                        size = px,
                        cornerRadius = r,
                    )
                    // 发丝描边：玻璃轮廓（按压时更亮，浮现 iOS 式光学轮廓）。
                    val hairline = 1.dp.toPx()
                    drawRoundRect(
                        color = tint.copy(alpha = 0.45f + 0.35f * edgeHighlight),
                        topLeft = Offset(hairline / 2f, hairline / 2f),
                        size = Size(px.width - hairline, px.height - hairline),
                        cornerRadius = CornerRadius(cornerRadiusPx),
                        style = Stroke(width = hairline),
                    )
                },
            ),
        )
    } else {
        // Canvas 兜底：半透明圆角矩形 + 柔光 + 1px 发丝描边，保持既有选中观感。
        Box(
            modifier = modifier.then(
                Modifier.drawWithContent {
                    val px = this.size
                    val radius = cornerRadiusPx
                    drawRoundRect(
                        color = tint.copy(alpha = 0.16f),
                        topLeft = Offset.Zero,
                        size = px,
                        cornerRadius = CornerRadius(radius),
                    )
                    drawRoundRect(
                        color = tint.copy(alpha = 0.10f),
                        topLeft = Offset(px.width * 0.18f, px.height * 0.12f),
                        size = Size(px.width * 0.64f, px.height * 0.76f),
                        cornerRadius = CornerRadius(radius * 0.8f),
                    )
                    val hairline = 1.dp.toPx()
                    drawRoundRect(
                        color = tint.copy(alpha = 0.5f),
                        topLeft = Offset(hairline / 2f, hairline / 2f),
                        size = Size(px.width - hairline, px.height - hairline),
                        cornerRadius = CornerRadius(radius),
                        style = Stroke(width = hairline),
                    )
                },
            ),
        )
    }
}
