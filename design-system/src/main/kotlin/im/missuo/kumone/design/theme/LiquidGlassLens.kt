// 液态玻璃「选中标尺」（selector lens）— 酷安 / IT之家 底部导航栏镜片风格移植。
//
// 方案（2026-10-23 第三轮，对照 IT之家/酷安真机录屏校准）：
// Compose 1.7.x 的 androidx.compose.ui.graphics.RenderEffect 未提供 RuntimeShader
// （AGSL）折射的公开封装（构造器 private、不可模块外继承），真折射做不了；本实现以
// 「毛玻璃 + 放大 + 滑动拉伸」三件套逼近 IT之家/酷安真机观感（对照录屏逐帧分析结论）：
//
//  1. 毛玻璃（frost）：折射源 = Compose live GraphicsLayer（KumoneApp.captureModifier
//     以 GPU record 持续录制底栏背后内容，零回读、与本帧同源），经 BlurEffect 实时模糊。
//     —— 解决旧 15fps toImageBitmap 回读的「烙旧帧 / 滑动卡顿」。
//  2. 放大（magnification）：把背后内容按 magnification 缩放后采进镜片（以镜片中心为
//     缩放锚点），近似真透镜的「放大镜」观感（录屏中手机产品图在镜片内明显变大弯曲）。
//  3. 滑动拉伸（stretch）：滑动速度驱动镜片横向拉长（blob），停下后回缩成常规药丸——
//     录屏中滑动时镜片膨胀到 1.5~2 个 Tab 宽，这是「液态」手感的最大来源。拉伸系数由
//     GlassTabBar 的逐帧速度环计算（70ms 低通），经 draw-time graphicsLayer 应用，
//     零重组。拉伸同时放大采样窗口（采样区按 1/sx 预压缩），拉长时看到更多内容而非糊成一团。
//
// 玻璃观感（中性玻璃填充 + 顶部高光 + 底部暗影 + 中性发丝描边 + 按压光学轮廓）由上层
// crisp 叠层绘制，不随模糊糊化，且不染品牌色（避免红色描边/填充）。底栏本体保持透明、
// 普通半透明玻璃（录屏确认 IT之家/酷安底栏本体均不模糊，只有选中镜片是毛玻璃）。
//
// 安全约束：BlurEffect 仅 API 33+ 可用，相关调用被 SDK_INT>=33 守卫；API<33 或无背后层
// 回落纯 Canvas 半透明药丸，零回归。

package im.missuo.kumone.design.theme

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt

/** 横向拉长很小（1.08 倍）：IT之家 的镜片基本维持一个 Tab 宽，不做横向拉长。 */
private const val STRETCH_MAX_X = 0.08f

/** 纵向膨胀：滑动时镜片再「探出」一点（液体被挤高），这是液态感的主要来源。 */
private const val STRETCH_MAX_Y = 0.12f

/**
 * 液态玻璃选中标尺。
 *
 * @param backdrop 底栏背后内容的 live [GraphicsLayer]（折射源，由 KumoneApp 的
 *   captureModifier 持续录制，与本帧合成同源，无回读、无滞后）。为 null 时回落 Canvas 兜底。
 * @param lensX 镜片在底栏本地坐标系中的 X 位置（px），每帧读取；镜片滑动时逐帧变化。
 * @param tint 预留品牌色（当前镜片观感保持中性玻璃，不染品牌色——避免红色描边/填充，
 *   选中态由 Tab 图标本身着色）。保留参数以便未来按主题注入。
 * @param edgeHighlight 边缘光学轮廓强度：按压 1f，其余 0f。
 * @param magnification 镜片内内容的放大倍率（近似真透镜；录屏校准约 1.15）。
 * @param stretch 拉伸进度 0..1（由 GlassTabBar 按滑动速度逐帧驱动）：0=静止常规药丸，
 *   1=滑动中最长 blob。draw-time 读取，零重组。
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
    frostRadiusDp: Dp = 8.dp,
    magnification: Float = 1.28f,
    stretch: () -> Float = { 0f },
) {
    val density = LocalDensity.current.density
    val cornerRadiusPx = cornerRadiusDp.value * density
    val frostRadiusPx = frostRadiusDp.value * density

    if (Build.VERSION.SDK_INT >= 33 && backdrop != null) {
        val blur = remember(frostRadiusPx) {
            BlurEffect(frostRadiusPx, frostRadiusPx, TileMode.Clamp)
        }
        Box(modifier = modifier) {
            // 拉伸层（blob）：滑动速度驱动横向拉长 + 纵向微胀。graphicsLayer 块内逐帧
            // 读取 stretch（State），只触发重绘不触发重组。以镜片中心为锚点缩放。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val u = stretch()
                        scaleX = 1f + STRETCH_MAX_X * u
                        scaleY = 1f + STRETCH_MAX_Y * u
                    },
            ) {
                // 下层：实时毛玻璃。修饰符顺序关键：clip 在外、graphicsLayer(BlurEffect)
                // 在内——模糊先产出再被裁到圆角，不会溢出镜片边界。
                //
                // 采样变换推导（配合外层拉伸 sx）：最终可见内容放大率 = magnification(m)，
                // 拉伸时采样窗口随之变宽（看到更多内容而非把模糊拉糊）。外层把绘制结果
                // 再拉长 sx，故此处采样按 m/sx 预压缩，两层相乘净放大率恰为 m：
                //   backdrop点p → 镜片内x = (p - 镜片中心在backdrop的x) * (m/sx) * sx + cell/2
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(cornerRadiusDp))
                        .graphicsLayer { renderEffect = blur }
                        .drawWithContent {
                            val u = stretch()
                            val sx = 1f + STRETCH_MAX_X * u
                            val sy = 1f + STRETCH_MAX_Y * u
                            // 液态感关键：拉伸时镜片内的内容也「跟着被拉长」（水感），
                            // 而不是保持固定放大率只是外框变宽。故采样只按 sqrt 预压缩：
                            // 净内容拉伸 = sqrt(sx)，画面随 blob 一起流动，同时不至于糊成一团。
                            // 另外运动中额外放大 10%，制造「被挤压鼓起」的液体观感。
                            val m = magnification + 0.10f * u
                            // 采样锚点 = 镜片中心在 backdrop 坐标系中的位置。
                            val cx = lensX() + size.width / 2f
                            translate(size.width / 2f, size.height / 2f) {
                                scale(
                                    scaleX = m / sqrt(sx),
                                    scaleY = m / sqrt(sy),
                                    pivot = Offset.Zero,
                                ) {
                                    translate(-cx, -size.height / 2f) {
                                        drawLayer(backdrop)
                                    }
                                }
                            }
                        },
                )
                // 上层：crisp 玻璃叠层（不模糊）——品牌 tint + 顶部高光 + 底部厚度暗影 +
                // 发丝描边 + 按压光学轮廓。随拉伸层一起变形成 blob 轮廓。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            val px = this.size
                            val r = CornerRadius(cornerRadiusPx)
                            // 纯透明液态玻璃：只保留「毛玻璃折射 + 放大 + 滑动拉伸」，
                            // 不铺白色填充、不画描边（白填充会在深色页面上形成白色块/轮廓）。
                            // 仅一道极淡的顶部反光勾出玻璃存在感，按压时略微增强。
                            drawRoundRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.18f + 0.10f * edgeHighlight),
                                        Color.White.copy(alpha = 0.0f),
                                    ),
                                    startY = 0f,
                                    endY = px.height * 0.55f,
                                ),
                                topLeft = Offset.Zero,
                                size = px,
                                cornerRadius = r,
                            )
                            // 玻璃边缘：一道极淡的白色发丝边，让 blob 的形状「看得见」——
                            // 液态拉伸全靠这条边被拉长才读得出来（不铺填充，所以不是白块）。
                            val hairline = 1.dp.toPx()
                            drawRoundRect(
                                color = Color.White.copy(alpha = 0.16f + 0.12f * edgeHighlight),
                                topLeft = Offset(hairline / 2f, hairline / 2f),
                                size = Size(px.width - hairline, px.height - hairline),
                                cornerRadius = CornerRadius(cornerRadiusPx),
                                style = Stroke(width = hairline),
                            )
                        },
                )
            }
        }
    } else {
        // Canvas 兜底：中性半透明圆角矩形 + 柔光 + 1px 发丝描边（不染品牌色）。
        Box(
            modifier = modifier.then(
                Modifier.drawWithContent {
                    val px = this.size
                    val radius = cornerRadiusPx
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.12f),
                        topLeft = Offset.Zero,
                        size = px,
                        cornerRadius = CornerRadius(radius),
                    )
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.08f),
                        topLeft = Offset(px.width * 0.18f, px.height * 0.12f),
                        size = Size(px.width * 0.64f, px.height * 0.76f),
                        cornerRadius = CornerRadius(radius * 0.8f),
                    )
                    val hairline = 1.dp.toPx()
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.5f),
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
