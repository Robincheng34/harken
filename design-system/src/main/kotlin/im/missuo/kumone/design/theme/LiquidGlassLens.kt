// 液态玻璃「选中标尺」（selector lens）— IT之家底部导航栏镜片移植。
//
// 基于 res/raw/liquid_glass_lens AGSL：对背后内容位图做折射 + 色散 + iOS
// 光学描边 + 按压驱动的 edgeHighlight + 品牌色 tint。
//
// 安全约束：RuntimeShader / BitmapShader / ShaderBrush 仅在 API 33+ 可用，
// 所有相关调用都被 Build.VERSION.SDK_INT >= 33 与 @RequiresApi(33) 守卫。
// API < 33 或无背后位图时，回落到纯 Canvas 绘制的半透明圆角药丸，保证零回归。

package im.missuo.kumone.design.theme

import android.content.Context
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import im.missuo.kumone.design.R

/**
 * 读取 res/raw/liquid_glass_lens 的 AGSL 源码（带 remember 缓存）。
 */
@Composable
fun rememberLensAgsl(context: Context): String {
    return remember(context) {
        context.resources
            .openRawResource(R.raw.liquid_glass_lens)
            .bufferedReader()
            .use { it.readText() }
    }
}

/**
 * 构建 IT之家「选中标尺」液态玻璃 [RuntimeShader]（仅固定 uniform，不含背后内容）。
 *
 * 该函数不含背后位图参数：AGSL 程序编译代价高，因此 [RuntimeShader] 只创建一次，
 * 背后内容位图由 [LiquidGlassLens] 在位图每次更新时通过 [RuntimeShader.setInputShader]
 * 热替换 `rmContent`，从而支持「实时折射」（内容滚动时持续更新）而不必每帧重编译。
 *
 * 注意：必须使用 framework 的 [BitmapShader]（不是 Compose 包装），因为
 * [RuntimeShader.setInputShader] 接收的是 [Shader]（android.graphics.Shader）。
 * （本 SDK 的 android.jar 未提供 API33 的 ImageShader，BitmapShader 是其等效的
 * framework 图像着色器，坐标 1:1 映射、CLAMP 平铺，满足镜片折射采样需求。）
 *
 * 调用方必须以 [Build.VERSION.SDK_INT] >= 33 守卫；本函数强制 [RequiresApi]。
 */
@RequiresApi(33)
fun createLensRuntimeShader(
    agsl: String,
    tint: Color,
    isDark: Boolean,
): RuntimeShader {
    val rs = RuntimeShader(agsl)
    rs.setFloatUniform("tintColor", tint.red, tint.green, tint.blue)
    // 完全透明：镜片不带任何颜色——既无品牌红染色，也无白底提亮。
    // 用户要求「导航栏完全透明不带任何颜色」：选中态可见性由红色图标/文案承担，
    // 镜片本身只做折射形变 + 色散 + 折射边缘，颜色全部来自背后真实内容像素。
    rs.setFloatUniform("tintAlpha", 0f)
    rs.setFloatUniform("isDark", if (isDark) 1f else 0f)
    rs.setFloatUniform("offset", 0f, 0f)
    rs.setFloatUniform("refractionAxis", 1f, 1f)
    rs.setFloatUniform("depthEffect", 0.75f)
    rs.setFloatUniform("dispersion", 0.22f)
    rs.setFloatUniform("contrast", 0.18f)
    // whitePoint = 0：不往镜片里混任何白色。镜片主体必须完全透明，让背后内容原样
    // 透出产生折射；一旦掺白，玻璃就会退化成一层半透明白色贴纸。
    rs.setFloatUniform("whitePoint", 0f)
    rs.setFloatUniform("chromaMultiplier", 1.2f)
    // rmContent 由 LiquidGlassLens 热替换；size/cornerRadius/refractionHeight/
    // refractionAmount/edgeHighlight 在绘制时设置。
    return rs
}

/**
 * 液态玻璃选中标尺。
 *
 * @param background 背后内容位图（折射源）；为 null 时回落 Canvas 兜底。
 *   支持整条底栏条带位图：配合 [backgroundOffsetX] 把采样窗口平移到镜片当前位置，
 *   支撑「选中镜片在 Tab 间滑动」而无需每帧重裁剪位图。
 * @param backgroundOffsetX 折射源在镜片本地坐标系中的 X 偏移读取器（px）。
 *   每帧在绘制期读取并经 [android.graphics.BitmapShader.setTranslate] 平移采样，
 *   滑动动画期间逐帧变化也不触发重组。
 * @param tint 品牌色（选中态 primary）。
 * @param edgeHighlight 边缘光学轮廓强度：按压 1f，其余 0f（静止态完全透明、不带描边色）。
 * @param cornerRadiusDp 镜片圆角。
 * @param refractionHeightDp 折射带高度。
 * @param refractionAmountDp 折射位移量。
 */
@Composable
fun LiquidGlassLens(
    background: ImageBitmap?,
    tint: Color,
    edgeHighlight: Float,
    modifier: Modifier = Modifier,
    cornerRadiusDp: Dp = 30.dp,
    refractionHeightDp: Dp = 48.dp,
    refractionAmountDp: Dp = 36.dp,
    backgroundOffsetX: () -> Float = { 0f },
) {
    val density = LocalDensity.current.density
    val cornerRadiusPx = cornerRadiusDp.value * density
    val refractionHeightPx = refractionHeightDp.value * density
    val refractionAmountPx = refractionAmountDp.value * density

    if (Build.VERSION.SDK_INT >= 33 && background != null) {
        val ctx = LocalContext.current
        val agsl = rememberLensAgsl(ctx)
        val bmp = remember(background) { background.asAndroidBitmap() }
        val isDark = isSystemInDarkTheme()
        // RuntimeShader 只随主题/品牌色创建一次；背后内容位图每次实时捕获都变，
        // 通过 remember(bmp) 重建 BitmapShader 并经 SideEffect 热替换 rmContent，
        // 实现「内容滚动→实时折射」而无需每帧重编译 AGSL。
        val rs = remember(agsl, tint, isDark) {
            createLensRuntimeShader(agsl, tint, isDark)
        }
        val contentInput = remember(bmp) {
            BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        // 采样矩阵复用（每帧 reset+postTranslate，不额外分配）。
        val localMatrix = remember(bmp) { android.graphics.Matrix() }
        Box(
            modifier = modifier.then(
                Modifier.drawWithContent {
                    val px = this.size
                    // 每帧绑定最新背后内容（contentInput 仅在 bmp 变化时重建），
                    // 实现实时折射而无需重编译 AGSL。
                    // setLocalMatrix：把整条条带位图平移到镜片本地坐标——镜片滑动到
                    // 位置 X 时，本地 (0,0) 对应条带的 (X, 0)，折射源随之跟手。
                    localMatrix.reset()
                    localMatrix.postTranslate(-backgroundOffsetX(), 0f)
                    contentInput.setLocalMatrix(localMatrix)
                    rs.setInputShader("rmContent", contentInput)
                    rs.setFloatUniform("size", px.width, px.height)
                    rs.setFloatUniform(
                        "cornerRadius",
                        cornerRadiusPx,
                        cornerRadiusPx,
                        cornerRadiusPx,
                        cornerRadiusPx,
                    )
                    rs.setFloatUniform("refractionHeight", refractionHeightPx)
                    rs.setFloatUniform("refractionAmount", refractionAmountPx)
                    rs.setFloatUniform("edgeHighlight", edgeHighlight)
                    drawRect(brush = ShaderBrush(rs), size = px)
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
                    // 半透明品牌色填充（与既有选中态 primary.copy(alpha=0.12f) 一致）。
                    drawRoundRect(
                        color = tint.copy(alpha = 0.16f),
                        topLeft = Offset(0f, 0f),
                        size = px,
                        cornerRadius = CornerRadius(radius),
                    )
                    // 柔和内辉光（品牌色，低 alpha）。
                    drawRoundRect(
                        color = tint.copy(alpha = 0.10f),
                        topLeft = Offset(px.width * 0.18f, px.height * 0.12f),
                        size = Size(px.width * 0.64f, px.height * 0.76f),
                        cornerRadius = CornerRadius(radius * 0.8f),
                    )
                    // 1px 发丝描边。
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
