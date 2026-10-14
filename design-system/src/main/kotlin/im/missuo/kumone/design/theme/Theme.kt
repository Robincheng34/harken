// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.design.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 品牌色（iOS `Theme.swift` 1:1 移植）。
 *
 * 网易云红略调暖：#EC4949 / 深色 #C92929；渐变起色 #F85B5B。
 */
val BrandRed: Color = Color(0xFFEC4949)
val BrandRedDeep: Color = Color(0xFFC92929)
val BrandRedBright: Color = Color(0xFFF85B5B)

/** 主色渐变（左上 → 右下），用于主按钮/播放键/FM 卡片。 */
val BrandGradient: Brush =
    Brush.linearGradient(listOf(BrandRedBright, BrandRedDeep))

private val LightColors = lightColorScheme(
    primary = BrandRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE3E3),
    onPrimaryContainer = BrandRedDeep,
    secondary = BrandRedDeep,
    onSecondary = Color.White,
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1C1B1F),
    surface = Color.White,
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFF5EDED),
    onSurfaceVariant = Color(0xFF6E5C5C),
    error = Color(0xFFD32F2F),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = BrandRedBright,
    onPrimary = Color(0xFF3E0000),
    primaryContainer = Color(0xFF7A1F1F),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = BrandRed,
    onSecondary = Color.White,
    background = Color(0xFF141213),
    onBackground = Color(0xFFECE0E0),
    surface = Color(0xFF141213),
    onSurface = Color(0xFFECE0E0),
    surfaceVariant = Color(0xFF524343),
    onSurfaceVariant = Color(0xFFD7C1C1),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
)

/**
 * Kumone 应用主题。
 *
 * @param darkTheme 覆盖 [im.missuo.kumone.storage.settings.AppAppearance] 的
 *   解析结果（AUTO → 系统、LIGHT/DARK → 强制）。
 */
@Composable
fun KumoneTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = KumoneTypography,
        shapes = KumoneShapes,
        content = content,
    )
}
