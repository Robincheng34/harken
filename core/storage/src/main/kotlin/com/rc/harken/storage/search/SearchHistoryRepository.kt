// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken.storage.search

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 搜索历史仓库（官方搜索页「搜索历史」）。
 *
 * DataStore 单键存储：以 ASCII Unit Separator（\u001F）分隔、最近搜索在前、
 * 去重、上限 [MAX_HISTORY] 条。读取为冷 [Flow]（无历史时为空列表），
 * 写入为 suspend `edit {}`。
 */
class SearchHistoryRepository(private val dataStore: DataStore<Preferences>) {

    /** 最近搜索（最新在前；空列表表示无历史）。 */
    val history: Flow<List<String>> = dataStore.data.map { prefs ->
        prefs[Keys.HISTORY]
            ?.split(SEPARATOR)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
    }

    /** 记录一次搜索：去重后置顶，超上限丢弃最旧。 */
    suspend fun add(keyword: String) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return
        dataStore.edit { prefs ->
            val existing = prefs[Keys.HISTORY]
                ?.split(SEPARATOR)
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() && it != trimmed }
                .orEmpty()
            val updated = (listOf(trimmed) + existing).take(MAX_HISTORY)
            prefs[Keys.HISTORY] = updated.joinToString(SEPARATOR)
        }
    }

    /** 删除单条历史。 */
    suspend fun remove(keyword: String) {
        dataStore.edit { prefs ->
            val remaining = prefs[Keys.HISTORY]
                ?.split(SEPARATOR)
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() && it != keyword }
                .orEmpty()
            prefs[Keys.HISTORY] = remaining.joinToString(SEPARATOR)
        }
    }

    /** 清空全部历史。 */
    suspend fun clear() {
        dataStore.edit { it[Keys.HISTORY] = "" }
    }

    private object Keys {
        val HISTORY = stringPreferencesKey("search.history")
    }

    private companion object {
        /** ASCII Unit Separator：正常搜索词不会包含，避免逗号等被关键词本身误伤。 */
        const val SEPARATOR = "\u001F"
        const val MAX_HISTORY = 20
    }
}
