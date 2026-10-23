// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 二维码编码与渲染（T16 `components/QrCodeView.kt`）。
//
// 使用 zxing-core 将登录 URL 编码为 `BitMatrix`，再绘制为 Android `Bitmap` 供
// Compose `Image` 展示（纯 JVM 编码，无 Android UI 依赖）。

package com.rc.harken.feature.login.components

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.rc.harken.feature.login.R

/** 将文本编码为方形二维码位图。 */
fun encodeQrBitmap(content: String, sizePx: Int = 360): Bitmap {
    val hints = mapOf(
        EncodeHintType.MARGIN to 1,
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val black = Color.BLACK
    val white = Color.WHITE
    for (x in 0 until sizePx) {
        for (y in 0 until sizePx) {
            bitmap.setPixel(x, y, if (matrix[x, y]) black else white)
        }
    }
    return bitmap
}

/** 二维码可组合：直接渲染编码后的位图。 */
@Composable
fun QrCodeView(
    content: String,
    modifier: Modifier = Modifier,
    sizeDp: Int = 220,
) {
    val bitmap = rememberQrBitmap(content, sizeDp)
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = stringResource(R.string.login_qr_image_desc),
        modifier = modifier.size(sizeDp.dp),
    )
}

@androidx.compose.runtime.Composable
private fun rememberQrBitmap(content: String, sizeDp: Int): Bitmap =
    androidx.compose.runtime.remember(content, sizeDp) {
        encodeQrBitmap(content, sizeDp * 2)
    }
