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
 * 用户指定主题默认红为 exp_red.png 的纯红色：#FF0000；渐变深色 #CC0000 / 亮色 #FF3333。
 */
val BrandRed: Color = Color(0xFFFF0000)
val BrandRedDeep: Color = Color(0xFFCC0000)
val BrandRedBright: Color = Color(0xFFFF3333)

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
 * @param primaryColor 主题挂件主色（默认官方红 [BrandRed]）。非默认值时覆盖
 *   配色方案的强调色（primary/secondary/primaryContainer/onPrimaryContainer），
 *   中性色（背景/表面/文字）保持不变，浅/深色逻辑不受影响。
 */
@Composable
fun KumoneTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    primaryColor: Color = BrandRed,
    content: @Composable () -> Unit,
) {
    val scheme = (if (darkTheme) DarkColors else LightColors).let { base ->
        if (primaryColor == BrandRed) {
            base
        } else {
            base.copy(
                primary = primaryColor,
                onPrimary = Color.White,
                secondary = primaryColor,
                primaryContainer = primaryColor.copy(alpha = if (darkTheme) 0.28f else 0.12f),
                onPrimaryContainer = if (darkTheme) Color.White else primaryColor,
            )
        }
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = KumoneTypography,
        shapes = KumoneShapes,
        content = content,
    )
}
