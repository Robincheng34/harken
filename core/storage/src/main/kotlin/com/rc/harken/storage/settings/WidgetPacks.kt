// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 主题挂件（官方「主题色」页 ThemeColorDetailActivity 行为对齐）与
// 主图挂件（官方「主题背景」页 ThemeBgDetailActivity/Fragment 行为对齐）的持久化数据。
//
// 基准：网易云 v9.6.05 反编译产物（06_反编译源代码 主题-皮肤 / 底层框架与基础库）：
//  - 主题色页 = 内置色板列表（ThemeColorDetailActivity.h 数组，逐字照搬，15 个预设色）
//    + 自定义取色（ColorPicker，HSV 模型）。本文件只保留官方有证据的预设色，自定义色
//    以 hex 字符串持久化（ColorPicker 自定义结果），不编造任何色值。
//  - 主题背景页 = 选背景图（ThemeBgDetailFragment.onActivityResult 走 ucrop 选图）
//    + buildColorList(Bitmap,int) 从图用 androidx.palette 提取色板。harken 用用户自选
//    图片 Uri 持久化复刻该行为；v1 背景源为用户自选图片。

package com.rc.harken.storage.settings

/** 主题分类：纯色系（官方预设）/ 渐变系（自研）/ 暗色系（自研）。 */
const val THEME_CATEGORY_SOLID = "solid"
const val THEME_CATEGORY_GRADIENT = "gradient"
const val THEME_CATEGORY_DARK = "dark"

/**
 * 预设主题色（主题挂件色板）。
 *
 * @param id 持久化用的稳定标识：即主色（或渐变起点色）的 6 位 hex（无前缀）；
 *   预设与自定义统一以 hex 存，[resolveThemePackPrimaryColor] 依此解析应用主色。
 * @param color ARGB 0xAARRGGBB，作为主题主色（官方单一主色架构，harken 目前只消费此色）。
 * @param color2 渐变终点色（仅用于画廊预览；可为 null，应用主色仍取 [color]）。
 * @param label 展示名（官方预设为颜色描述，自研为命名主题）；null 时 UI 回落显示 hex。
 * @param category 分组：solid / gradient / dark，供主题馆画廊按系展示。
 * @param official 是否官方 v9.6.05 反编译证据中的预设（true）；自研本地精选为 false。
 */
data class ThemeColorPack(
    val id: String,
    val color: Long,
    val color2: Long? = null,
    val label: String? = null,
    val category: String = THEME_CATEGORY_SOLID,
    val official: Boolean = true,
)

/** 应用默认（品牌红 #FF0000），[resolveThemePackPrimaryColor] 在 null / 不可解析时回落。 */
const val THEME_COLOR_DEFAULT_VALUE: Long = 0xFFFF0000

/**
 * 主题馆精选色板（本地，非官方在线装扮中心）。
 *
 * 第一部分：官方 v9.6.05 主题色页预设色（ThemeColorDetailActivity.h 数组逐字照搬，15 个，official = true）。
 * 第二部分：自研本地精选（渐变系 / 暗色系，official = false，id 均为唯一 6 位 hex，主色取渐变起点）。
 *   官方在线装扮中心为 WebView H5（无可用端点、未接入），本列表明确标注来源、不与官方混淆。
 */
val CURATED_THEME_PACKS: List<ThemeColorPack> = listOf(
    // ── 官方预设（纯色系） ──
    ThemeColorPack("313638", 0xFF313638L, label = "石墨黑", category = THEME_CATEGORY_SOLID),
    ThemeColorPack("9c27b0", 0xFF9C27B0L, label = "紫罗兰", category = THEME_CATEGORY_SOLID),
    ThemeColorPack("673ab7", 0xFF673AB7L, label = "深紫", category = THEME_CATEGORY_SOLID),
    ThemeColorPack("3f51b5", 0xFF3F51B5L, label = "靛蓝", category = THEME_CATEGORY_SOLID),
    ThemeColorPack("2196f3", 0xFF2196F3L, label = "天蓝", category = THEME_CATEGORY_SOLID),
    ThemeColorPack("00bcd4", 0xFF00BCD4L, label = "青蓝", category = THEME_CATEGORY_SOLID),
    ThemeColorPack("ffa000", 0xFFFFA000L, label = "琥珀", category = THEME_CATEGORY_SOLID),
    ThemeColorPack("b8c500", 0xFFB8C500L, label = "柠檬", category = THEME_CATEGORY_SOLID),
    ThemeColorPack("8bc34a", 0xFF8BC34AL, label = "草绿", category = THEME_CATEGORY_SOLID),
    ThemeColorPack("4caf50", 0xFF4CAF50L, label = "绿", category = THEME_CATEGORY_SOLID),
    ThemeColorPack("009688", 0xFF009688L, label = "蓝绿", category = THEME_CATEGORY_SOLID),
    ThemeColorPack("f57c00", 0xFFF57C00L, label = "橙", category = THEME_CATEGORY_SOLID),
    ThemeColorPack("f44336", 0xFFF44336L, label = "红", category = THEME_CATEGORY_SOLID),
    ThemeColorPack("e91e63", 0xFFE91E63L, label = "玫红", category = THEME_CATEGORY_SOLID),
    ThemeColorPack("c2185b", 0xFFC2185BL, label = "品红", category = THEME_CATEGORY_SOLID),
    // ── 自研本地精选（渐变系，official = false） ──
    ThemeColorPack("7c4dff", 0xFF7C4DFFL, color2 = 0xFF448AFFL, label = "霓虹紫", category = THEME_CATEGORY_GRADIENT, official = false),
    ThemeColorPack("ff6b6b", 0xFFFF6B6BL, color2 = 0xFFFFD93DL, label = "晨曦", category = THEME_CATEGORY_GRADIENT, official = false),
    ThemeColorPack("1a2980", 0xFF1A2980L, color2 = 0xFF26D0CEL, label = "深海", category = THEME_CATEGORY_GRADIENT, official = false),
    ThemeColorPack("11998e", 0xFF11998EL, color2 = 0xFF38EF7DL, label = "森野", category = THEME_CATEGORY_GRADIENT, official = false),
    ThemeColorPack("8e2de2", 0xFF8E2DE2L, color2 = 0xFF4A00E0L, label = "莓果", category = THEME_CATEGORY_GRADIENT, official = false),
    // ── 自研本地精选（暗色系，official = false） ──
    ThemeColorPack("37474f", 0xFF37474FL, label = "碳灰", category = THEME_CATEGORY_DARK, official = false),
    ThemeColorPack("0d47a1", 0xFF0D47A1L, label = "夜蓝", category = THEME_CATEGORY_DARK, official = false),
    ThemeColorPack("4a148c", 0xFF4A148CL, label = "暗紫", category = THEME_CATEGORY_DARK, official = false),
    ThemeColorPack("1b5e20", 0xFF1B5E20L, label = "墨绿", category = THEME_CATEGORY_DARK, official = false),
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
