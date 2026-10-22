// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.storage.settings

import im.missuo.kumone.playback.AudioQuality

/** 外观模式：跟随系统 / 浅色 / 深色（iOS `AppAppearance`）。 */
enum class AppAppearance(val displayName: String) {
    AUTO("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
    ;

    companion object {
        fun fromKey(key: String?): AppAppearance =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: AUTO
    }
}

/**
 * 播放页模式（iOS `NowPlayingMode`）。
 *
 * v1.0 主理人已定仅 VINYL + IMMERSIVE；CLASSIC / MINIMAL 字段保留但设置页
 * 不展示（为后续版本预留，避免数据迁移）。
 */
enum class NowPlayingMode(val displayName: String) {
    VINYL("黑胶模式"),
    CLASSIC("经典模式"),
    IMMERSIVE("沉浸模式"),
    MINIMAL("简洁模式"),
    ;

    companion object {
        fun fromKey(key: String?): NowPlayingMode =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: VINYL
    }
}

/** 日语歌词上方注解：关闭 / 罗马音 / 汉字读音（iOS `LyricsAnnotation`）。 */
enum class LyricsAnnotation(val displayName: String) {
    OFF("关闭"),
    ROMAJI("罗马音"),
    FURIGANA("汉字读音"),
    ;

    companion object {
        fun fromKey(key: String?): LyricsAnnotation =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: OFF
    }
}

/**
 * 底部导航栏 Tab 配置项。
 *
 * [key] 与 `:app` 模块 `AppTab.name` 保持字符串契约（core 层不能依赖 app 层，
 * 双方以 key 对齐）；设置页用于展示与编辑，app 层据此还原为 [AppTab]。
 */
enum class NavTabOption(val key: String, val label: String) {
    HOME("HOME", "首页"),
    EXPLORE("EXPLORE", "发现"),
    SEARCH("SEARCH", "搜索"),
    FM("FM", "漫游"),
    LIBRARY("LIBRARY", "我的"),
    ;

    companion object {
        fun fromKey(key: String?): NavTabOption? =
            entries.firstOrNull { it.key == key }
    }
}

/**
 * 全部用户设置（iOS `SettingsManager` 的可观察状态）。
 *
 * 默认值与 iOS 一致：音质极高（exhigh）、灰色解锁默认开（与 iOS 一致，三源全接）、
 * 外观跟随系统、播放页黑胶、显示翻译、逐字歌词开、注解关。
 *
 * [tabOrder] 为「可见且按显示顺序排列」的底部 Tab key 列表；为空表示全部
 * Tab 按默认顺序显示。不在列表中的 Tab 即被隐藏（至少保留一个可见）。
 */
data class AppSettings(
    val audioQuality: AudioQuality = AudioQuality.EXHIGH,
    val unblockEnabled: Boolean = true,
    val appearance: AppAppearance = AppAppearance.AUTO,
    val nowPlayingMode: NowPlayingMode = NowPlayingMode.VINYL,
    val showTranslation: Boolean = true,
    val verbatimLyrics: Boolean = true,
    val lyricsAnnotation: LyricsAnnotation = LyricsAnnotation.OFF,
    // 迷你播放条：下滑收起开关（默认开，保留现有行为）+ 实验性「收起后显示把手」重新唤起入口。
    val swipeCollapseEnabled: Boolean = true,
    val collapseHandleEnabled: Boolean = false,
    // 最近播放/近期删除确认：开启后左滑停顿出现红条，需继续滑动或点击红区才删除（默认关，保持旧的一滑即删）。
    val recentDeleteGuardEnabled: Boolean = false,
    val tabOrder: List<String> = emptyList(),
    // 主题 / 主图挂件：存稳定 id，null = 未配置（取各自默认套件）。
    val activeThemePackId: String? = null,
    val activeMainImagePackId: String? = null,
)
