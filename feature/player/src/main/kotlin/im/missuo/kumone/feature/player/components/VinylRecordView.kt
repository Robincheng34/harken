// Ported from kumone by missuo (LGPL-3.0)
//
// 黑胶唱片（T26 `components/VinylRecordView.kt`）——官方客户端「半透明白胶」形态。
//
// 网易云官方播放页唱片观感：盘体为半透明白色塑料（背景封面可透出），外沿亮环 +
// 细密刻纹 + 柔和扫光；中心纸标为专辑封面，整盘（含纸标）随播放旋转。
// 矢量分层：透光盘体 → 外沿亮环/导光槽 → 刻纹圈 → 扇形扫光 → 中心纸标chrome。

package im.missuo.kumone.feature.player.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import im.missuo.kumone.net.model.Formatters
import kotlinx.coroutines.isActive

/** 每转一圈时长（33⅓ rpm 观感，对应 iOS `RecordRotationState`）。 */
private const val ROTATION_PERIOD_MS = 1800

/** 刻纹圈数（iOS `ForEach(0..<18)`）。 */
private const val GROOVE_COUNT = 18

/** iOS: `labelDiameter = discDiameter * 0.64`（网易云黑胶比例）。 */
private const val LABEL_RATIO = 0.64f

/**
 * 黑胶唱片（官方客户端半透明白胶形态）。
 *
 * @param coverURL 中心纸标封面。
 * @param size 唱片直径。
 * @param isPlaying 播放中旋转、暂停时静止（iOS 播放态判断）。
 */
@Composable
fun VinylRecordView(
    coverURL: String?,
    size: Dp,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    // 原 bug：rememberInfiniteTransition 永不停——暂停时仍每帧重绘，且
    // angle 直接取 0 导致角度跳回。改为 Animatable + LaunchedEffect(isPlaying)：
    // 播放时从当前角度持续 +360 线性旋转；暂停停在当前角度、无动画协程。
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (isActive) {
                rotation.animateTo(
                    targetValue = rotation.value + 360f,
                    animationSpec = tween(durationMillis = ROTATION_PERIOD_MS, easing = LinearEasing),
                )
            }
        }
    }
    val angle = rotation.value

    Box(modifier = modifier.size(size + 6.dp), contentAlignment = Alignment.Center) {
        // 盘体（含纸标框 / 金线环 / 主轴孔）整盘旋转。
        Canvas(modifier = Modifier.fillMaxSize()) {
            val disc = this.size.minDimension - 6.dp.toPx()
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            drawVinylDisc(center = center, diameter = disc, rotationDegrees = angle)
            rotate(degrees = angle, pivot = center) {
                drawLabelChrome(center = center, discDiameter = disc)
            }
        }
        if (coverURL != null) {
            // 中心封面：圆形裁剪 + graphicsLayer 同步旋转（AsyncImage 是 Composable，
            // 不能放进 DrawScope，故用图层旋转而非 drawScope.rotate）。
            AsyncImage(
                model = Formatters.resizedImageURL(coverURL, 384),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(size * LABEL_RATIO)
                    .graphicsLayer(rotationZ = angle)
                    .clip(CircleShape),
            )
        }
    }
}

/** 半透明白胶盘体逐层绘制（不含中心纸标）——官方客户端观感。 */
private fun DrawScope.drawVinylDisc(
    center: Offset,
    diameter: Float,
    rotationDegrees: Float,
) {
    val radius = diameter / 2f

    // 1. 透光盘体：白色低透明度径向渐变（封面背景自盘心向外隐约透出）。
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.08f),
                Color.White.copy(alpha = 0.12f),
                Color.White.copy(alpha = 0.18f),
                Color.White.copy(alpha = 0.22f),
            ),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )

    // 2. 外沿亮环（白胶边缘的高光塑封感）。
    drawCircle(
        color = Color.White.copy(alpha = 0.55f),
        radius = radius - 0.75.dp.toPx(),
        center = center,
        style = Stroke(width = 1.5f * density),
    )
    drawCircle(
        color = Color.White.copy(alpha = 0.16f),
        radius = radius - 3.dp.toPx(),
        center = center,
        style = Stroke(width = 0.6f * density),
    )

    rotate(degrees = rotationDegrees, pivot = center) {
        // 3. 细密刻纹（白圈低透明度，每 4 圈为主刻纹）。
        for (i in 0 until GROOVE_COUNT) {
            val factor = 0.68f + (i / (GROOVE_COUNT - 1f)) * 0.29f
            val isMajor = i % 4 == 0
            drawCircle(
                color = Color.White.copy(alpha = if (isMajor) 0.16f else 0.08f),
                radius = radius * factor,
                center = center,
                style = Stroke(width = (if (isMajor) 0.9f else 0.5f) * density),
            )
        }

        // 4. 内圈死区导光槽（纸标外沿）。
        drawCircle(
            color = Color.White.copy(alpha = 0.28f),
            radius = radius * 0.665f,
            center = center,
            style = Stroke(width = 1.2f * density),
        )

        // 5. 柔和扇形扫光（保留黑胶的转面反光，透明度收敛）。
        drawSweepSheen(center, radius - 1.dp.toPx(), rotationDegrees = 35f)
        drawSweepSheen(center, radius - 1.dp.toPx(), rotationDegrees = 215f)
    }
}

/** 中心纸标装饰（金线环 + 金属主轴孔）。 */
private fun DrawScope.drawLabelChrome(center: Offset, discDiameter: Float) {
    val labelRadius = discDiameter * LABEL_RATIO / 2f
    val spindle = maxOf(7f, discDiameter * 0.032f)

    // 复古金线环（0.86 label）
    drawCircle(
        brush = Brush.linearGradient(
            colors = listOf(Color.White.copy(alpha = 0.28f), Color.White.copy(alpha = 0.08f)),
            start = Offset(center.x - labelRadius, center.y - labelRadius),
            end = Offset(center.x + labelRadius, center.y + labelRadius),
        ),
        radius = labelRadius * 0.86f,
        center = center,
        style = Stroke(width = 0.8f * density),
    )

    // 金属主轴孔环
    drawCircle(
        brush = Brush.linearGradient(
            colors = listOf(
                Color(0xFFF2F2F2),
                Color(0xFFA6A6A6),
                Color(0xFFE6E6E6),
                Color(0xFF666666),
            ),
            start = Offset(center.x - spindle, center.y - spindle),
            end = Offset(center.x + spindle, center.y + spindle),
        ),
        radius = spindle * 1.2f,
        center = center,
    )
    // 主轴孔
    drawCircle(color = Color(0xFF0A0A0A), radius = spindle / 2f, center = center)
}

/** 单扇形镜面扫光（iOS MARK 4）。 */
private fun DrawScope.drawSweepSheen(center: Offset, radius: Float, rotationDegrees: Float) {
    val stops = arrayOf(
        0.00f to Color.Transparent,
        0.08f to Color.White.copy(alpha = 0.02f),
        0.14f to Color.White.copy(alpha = 0.10f),
        0.17f to Color(0xFFD9F2FF).copy(alpha = 0.14f),
        0.20f to Color.White.copy(alpha = 0.10f),
        0.26f to Color.White.copy(alpha = 0.02f),
        0.34f to Color.Transparent,
        0.50f to Color.Transparent,
        0.58f to Color.White.copy(alpha = 0.02f),
        0.64f to Color.White.copy(alpha = 0.10f),
        0.67f to Color(0xFFD9F2FF).copy(alpha = 0.14f),
        0.70f to Color.White.copy(alpha = 0.10f),
        0.76f to Color.White.copy(alpha = 0.02f),
        0.84f to Color.Transparent,
        1.00f to Color.Transparent,
    )
    rotate(degrees = rotationDegrees, pivot = center) {
        drawCircle(
            brush = Brush.sweepGradient(colorStops = stops, center = center),
            radius = radius,
            center = center,
        )
    }
}
