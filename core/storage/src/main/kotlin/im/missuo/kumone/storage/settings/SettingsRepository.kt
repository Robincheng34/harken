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
            tabOrder = this[Keys.TAB_ORDER]
                ?.split(",")
                ?.mapNotNull { raw -> NavTabOption.fromKey(raw.trim())?.key }
                .orEmpty(),
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
    }
}
