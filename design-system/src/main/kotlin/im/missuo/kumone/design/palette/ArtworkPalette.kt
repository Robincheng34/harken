// Ported from kumone by missuo (LGPL-3.0)
//
// 封面取色（iOS `ArtworkPalette` 1:1 移植）：从封面 URL 提取 dominent / vibrant /
// muted 三色，用于全屏播放页渐变背景。基于 androidx.palette + Coil 解码。

package im.missuo.kumone.design.palette

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil.Coil
import coil.request.ImageRequest
import coil.request.SuccessResult
import im.missuo.kumone.design.theme.BrandRed
import im.missuo.kumone.design.theme.BrandRedDeep

/**
 * 由封面位图抽取的三色调色板。
 */
data class ArtworkPalette(
    val dominant: Color,
    val vibrant: Color,
    val muted: Color,
) {
    companion object {
        val Default: ArtworkPalette =
            ArtworkPalette(dominant = BrandRedDeep, vibrant = BrandRed, muted = Color(0xFF9E9E9E))
    }
}

/** 封面取色器：从 URL 异步解码并生成调色板，失败/空 URL 回退 [ArtworkPalette.Default]。 */
object ArtworkPaletteExtractor {
    suspend fun extract(context: Context, url: String?): ArtworkPalette {
        if (url.isNullOrEmpty()) return ArtworkPalette.Default
        return runCatching {
            val loader = Coil.imageLoader(context)
            val request = ImageRequest.Builder(context)
                .data(url)
                .allowHardware(false)
                .size(96, 96)
                .build()
            val result = loader.execute(request)
            val drawable = (result as? SuccessResult)?.drawable
                ?: return@runCatching ArtworkPalette.Default
            val bitmap: Bitmap = drawable.toBitmap(96, 96)
            val palette: Palette = Palette.from(bitmap).generate()
            ArtworkPalette(
                dominant = Color(palette.getDominantColor(BrandRedDeep.value.toInt())),
                vibrant = Color(palette.getVibrantColor(BrandRed.value.toInt())),
                muted = Color(palette.getMutedColor(0xFF9E9E9E.toInt())),
            )
        }.getOrDefault(ArtworkPalette.Default)
    }
}

/**
 * 组合侧封面取色：在协程中解码 URL 并暴露 [ArtworkPalette] 状态（默认 [ArtworkPalette.Default]）。
 */
@Composable
fun rememberArtworkPalette(url: String?, context: Context): State<ArtworkPalette> =
    produceState(initialValue = ArtworkPalette.Default, key1 = url, key2 = context) {
        value = ArtworkPaletteExtractor.extract(context, url)
    }
