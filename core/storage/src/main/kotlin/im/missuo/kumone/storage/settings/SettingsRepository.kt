// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.storage.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import im.missuo.kumone.playback.AudioQuality
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore-backed settings store (iOS `SettingsManager`).
 *
 * Reads are a cold [Flow] of [AppSettings]; every mutation is a suspend
 * `edit {}`. Keys mirror the iOS UserDefaults keys so both ports share the
 * same vocabulary in bug reports.
 */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<AppSettings> = dataStore.data.map { it.toAppSettings() }

    suspend fun setAudioQuality(quality: AudioQuality) = editString(Keys.QUALITY, quality.apiLevel)

    suspend fun setUnblockEnabled(enabled: Boolean) = editBoolean(Keys.UNBLOCK, enabled)

    suspend fun setAppearance(appearance: AppAppearance) = editString(Keys.APPEARANCE, appearance.name)

    suspend fun setNowPlayingMode(mode: NowPlayingMode) = editString(Keys.NOW_PLAYING_MODE, mode.name)

    suspend fun setShowTranslation(show: Boolean) = editBoolean(Keys.SHOW_TRANSLATION, show)

    suspend fun setVerbatimLyrics(enabled: Boolean) = editBoolean(Keys.VERBATIM_LYRICS, enabled)

    suspend fun setLyricsAnnotation(annotation: LyricsAnnotation) =
        editString(Keys.LYRICS_ANNOTATION, annotation.name)

    /**
     * 保存底部导航栏配置：[keys] 为可见 Tab 的 key（[NavTabOption.key]），
     * 顺序即显示顺序；列表外的 Tab 被隐藏。空串表示恢复默认（全部显示）。
     */
    suspend fun setTabOrder(keys: List<String>) =
        editString(Keys.TAB_ORDER, keys.joinToString(","))

    // 套件（主题挂件 / 主图挂件）：DataStore 只存稳定 id，空串表示未配置。
    suspend fun setActiveThemePack(id: String?) = editString(Keys.THEME_PACK, id ?: "")

    suspend fun setActiveMainImagePack(id: String?) = editString(Keys.MAIN_IMAGE_PACK, id ?: "")

    /** 迷你播放条下滑收起开关。 */
    suspend fun setSwipeCollapseEnabled(enabled: Boolean) = editBoolean(Keys.SWIPE_COLLAPSE, enabled)

    /** 实验性：收起后显示把手，点击 / 上滑重新唤起。 */
    suspend fun setCollapseHandleEnabled(enabled: Boolean) = editBoolean(Keys.COLLAPSE_HANDLE, enabled)

    /** 最近播放 / 近期删除确认开关。 */
    suspend fun setRecentDeleteGuardEnabled(enabled: Boolean) = editBoolean(Keys.RECENT_DELETE_GUARD, enabled)

    /** 当前主题挂件 id（null = 未配置）。供主题应用层读取。 */
    val activeThemePackId: Flow<String?> = settings.map { it.activeThemePackId }

    /** 当前主图挂件 id（null = 未配置）。供「我的」页覆盖头部背景时读取。 */
    val activeMainImagePackId: Flow<String?> = settings.map { it.activeMainImagePackId }

    private suspend fun editString(key: Preferences.Key<String>, value: String) {
        dataStore.edit { it[key] = value }
    }

    private suspend fun editBoolean(key: Preferences.Key<Boolean>, value: Boolean) {
        dataStore.edit { it[key] = value }
    }

    private fun Preferences.toAppSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            audioQuality = this[Keys.QUALITY]
                ?.let { stored -> AudioQuality.entries.firstOrNull { it.apiLevel == stored } }
                ?: defaults.audioQuality,
            unblockEnabled = this[Keys.UNBLOCK] ?: defaults.unblockEnabled,
            appearance = AppAppearance.fromKey(this[Keys.APPEARANCE]),
            nowPlayingMode = NowPlayingMode.fromKey(this[Keys.NOW_PLAYING_MODE]),
            showTranslation = this[Keys.SHOW_TRANSLATION] ?: defaults.showTranslation,
            verbatimLyrics = this[Keys.VERBATIM_LYRICS] ?: defaults.verbatimLyrics,
            lyricsAnnotation = LyricsAnnotation.fromKey(this[Keys.LYRICS_ANNOTATION]),
            swipeCollapseEnabled = this[Keys.SWIPE_COLLAPSE] ?: defaults.swipeCollapseEnabled,
            collapseHandleEnabled = this[Keys.COLLAPSE_HANDLE] ?: defaults.collapseHandleEnabled,
            recentDeleteGuardEnabled = this[Keys.RECENT_DELETE_GUARD] ?: defaults.recentDeleteGuardEnabled,
            tabOrder = this[Keys.TAB_ORDER]
                ?.split(",")
                ?.mapNotNull { raw -> NavTabOption.fromKey(raw.trim())?.key }
                .orEmpty(),
            activeThemePackId = this[Keys.THEME_PACK].takeIf { !it.isNullOrEmpty() },
            activeMainImagePackId = this[Keys.MAIN_IMAGE_PACK].takeIf { !it.isNullOrEmpty() },
        )
    }

    private object Keys {
        val QUALITY = stringPreferencesKey("settings.audioQuality")
        val APPEARANCE = stringPreferencesKey("settings.appearance")
        val NOW_PLAYING_MODE = stringPreferencesKey("settings.nowPlayingMode")
        val SHOW_TRANSLATION = booleanPreferencesKey("settings.showLyricsTranslation")
        val VERBATIM_LYRICS = booleanPreferencesKey("settings.verbatimLyrics")
        val LYRICS_ANNOTATION = stringPreferencesKey("settings.lyricsAnnotation")
        val UNBLOCK = booleanPreferencesKey("settings.enableUnblock")
        val TAB_ORDER = stringPreferencesKey("settings.tabOrder")
        val THEME_PACK = stringPreferencesKey("settings.themePack")
        val MAIN_IMAGE_PACK = stringPreferencesKey("settings.mainImagePack")
        val SWIPE_COLLAPSE = booleanPreferencesKey("settings.swipeCollapse")
        val COLLAPSE_HANDLE = booleanPreferencesKey("settings.collapseHandle")
        val RECENT_DELETE_GUARD = booleanPreferencesKey("settings.recentDeleteGuard")
    }
}
