// Ported from kumone by missuo (LGPL-3.0)
//
// 主题挂件（官方「主题色」页 ThemeColorDetailActivity 行为对齐）与
// 主图挂件（官方「主题背景」页 ThemeBgDetailActivity/Fragment 行为对齐）的持久化数据。
//
// 基准：网易云 v9.6.05 反编译产物（06_反编译源代码 主题-皮肤 / 底层框架与基础库）：
//  - 主题色页 = 内置色板列表（ThemeColorDetailActivity.h 数组，逐字照搬，15 个预设色）
//    + 自定义取色（ColorPicker，HSV 模型）。本文件只保留官方有证据的预设色，自定义色
//    以 hex 字符串持久化（ColorPicker 自定义结果），不编造任何色值。
//  - 主题背景页 = 选背景图（ThemeBgDetailFragment.onActivityResult 走 ucrop 选图）
//    + buildColorList(Bitmap,int) 从图用 androidx.palette 提取色板。kumone 用用户自选
//    图片 Uri 持久化复刻该行为；v1 背景源为用户自选图片。

package im.missuo.kumone.storage.settings

/**
 * 预设主题色（主题挂件色板）。
 *
 * @param id 持久化用的稳定标识：即该色的 6 位 hex（无前缀）；预设与自定义统一以 hex 存。
 * @param color ARGB 0xAARRGGBB。
 */
data class ThemeColorPack(
    val id: String,
    val color: Long,
)

/** 应用默认（品牌红 #FF0000），[resolveThemePackPrimaryColor] 在 null / 不可解析时回落。 */
const val THEME_COLOR_DEFAULT_VALUE: Long = 0xFFFF0000

/**
 * 官方 v9.6.05 主题色页预设色板（ThemeColorDetailActivity.h 数组逐字照搬，非编造）。
 * 末尾「自定义取色」入口不在本列表，由设置页 UI 单独渲染（HSV 取色）。
 */
val OFFICIAL_THEME_COLORS: List<ThemeColorPack> = listOf(
    ThemeColorPack("313638", 0xFF313638L),
    ThemeColorPack("9c27b0", 0xFF9C27B0L),
    ThemeColorPack("673ab7", 0xFF673AB7L),
    ThemeColorPack("3f51b5", 0xFF3F51B5L),
    ThemeColorPack("2196f3", 0xFF2196F3L),
    ThemeColorPack("00bcd4", 0xFF00BCD4L),
    ThemeColorPack("ffa000", 0xFFFFA000L),
    ThemeColorPack("b8c500", 0xFFB8C500L),
    ThemeColorPack("8bc34a", 0xFF8BC34AL),
    ThemeColorPack("4caf50", 0xFF4CAF50L),
    ThemeColorPack("009688", 0xFF009688L),
    ThemeColorPack("f57c00", 0xFFF57C00L),
    ThemeColorPack("f44336", 0xFFF44336L),
    ThemeColorPack("e91e63", 0xFFE91E63L),
    ThemeColorPack("c2185b", 0xFFC2185BL),
)

/**
 * 解析主题挂件（主题色）主色 ARGB 0xAARRGGBB。
 *
 * 持久化值统一为 6/8 位 hex（无前缀）：预设色用其 hex id，自定义色用 ColorPicker 结果。
 * null / 空 / 不可解析 → 应用默认品牌红。
 */
fun resolveThemePackPrimaryColor(packId: String?): Long {
    if (packId.isNullOrEmpty()) return THEME_COLOR_DEFAULT_VALUE
    val hex = packId.trim().removePrefix("#")
    return when (hex.length) {
        6 -> 0xFF000000L or (hex.toLongOrNull(16) ?: THEME_COLOR_DEFAULT_VALUE)
        8 -> hex.toLongOrNull(16) ?: THEME_COLOR_DEFAULT_VALUE
        else -> THEME_COLOR_DEFAULT_VALUE
    }
}
