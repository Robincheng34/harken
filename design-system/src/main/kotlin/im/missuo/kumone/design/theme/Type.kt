// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.design.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 字体规格（iOS 设计 Token 1:1 移植）。
 *
 * - 卡片标题 13sp（歌单/专辑网格主标题）
 * - 副标题 11sp（播放量、歌手名等次级信息）
 * - 播放页歌名 22sp bold
 * - 歌词：当前行 16sp semibold / 非当前行 14sp
 */
private val Default = Typography()

val KumoneTypography: Typography = Typography(
    // 卡片标题 13sp。
    titleMedium = Default.titleMedium.copy(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    // 副标题 11sp。
    bodySmall = Default.bodySmall.copy(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    ),
    // 播放页歌名 22sp bold。
    headlineSmall = Default.headlineSmall.copy(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    // 歌词当前行 16sp semibold。
    titleLarge = Default.titleLarge.copy(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    // 歌词非当前行 14sp。
    bodyMedium = Default.bodyMedium.copy(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 22.sp,
    ),
)

/** 歌词当前行大字（沉浸歌词 28sp，超出 Typography 槽位单独暴露）。 */
val LyricsActiveLineStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Bold,
    fontSize = 28.sp,
    lineHeight = 40.sp,
)
