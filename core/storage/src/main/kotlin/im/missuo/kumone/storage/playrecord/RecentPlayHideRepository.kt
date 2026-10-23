// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.storage.playrecord

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * 「最近播放」本地删除标记（tombstone）仓储。
 *
 * 背景：官方删除播放记录的接口在逆向产物里不存在，本项目 `NeteaseApi.deletePlayRecord`
 * 是自编端点、服务端不认——删除只能靠本地把记录隐藏掉。而进程内单例（
 * `PlayRecordHideStore`）一重启就清空，导致「删了重启又回来」。
 *
 * 本仓储把隐藏键落盘到与 Cookie / 设置同一个 DataStore，实现跨进程重启保持删除态。
 * 键由 UI 侧 `playRecordKey(song)` 生成（曲名|歌手|时长），与播放次数无关。
 */
class RecentPlayHideRepository(private val dataStore: DataStore<Preferences>) {

    /** 读取已删除（隐藏）的记录键集合；无记录时为空集合。 */
    suspend fun load(): Set<String> = dataStore.data.first()[Keys.HIDDEN].orEmpty()

    /** 覆写整份隐藏键集合（单曲删除 / 清空 / 重新播放取消隐藏都走这里）。 */
    suspend fun save(keys: Set<String>) {
        dataStore.edit { prefs ->
            if (keys.isEmpty()) {
                prefs.remove(Keys.HIDDEN)
            } else {
                prefs[Keys.HIDDEN] = keys
            }
        }
    }

    private object Keys {
        val HIDDEN = stringSetPreferencesKey("recent.play.hidden.keys")
    }
}
