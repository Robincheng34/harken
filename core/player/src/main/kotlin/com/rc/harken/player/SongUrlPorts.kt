// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// `SongUrlResolver` 的窄端口（可注入、可纯 JVM 单测）。
//
// 与 `feature/fm` 的 `FmPlaybackPort` 同一思路：`SongUrlResolver` 原先直接依赖
// `NeteaseApi` 与 final class `SettingsRepository`（后者构造需真实 DataStore，属
// Android 依赖），导致核心播放链路无法在纯 JVM 下测试。这里把两者收窄为两个最小
// 接口，真实实现用适配器包一层——既保持依赖方向（core/player 仍只依赖 core/net、
// core/storage），又让音质回落链路可被伪实现驱动。

package com.rc.harken.player

import com.rc.harken.net.api.NeteaseApi
import com.rc.harken.net.model.SongURLData
import com.rc.harken.storage.settings.SettingsRepository
import kotlinx.coroutines.flow.first

/** 解析所需的最小取数能力（`NeteaseApi` 已满足其语义）。 */
interface SongUrlApi {
    suspend fun songURL(ids: List<Long>, level: String): List<SongURLData>
}

/** 解析所需的最小设置能力（`SettingsRepository` 已满足其语义）。 */
interface UnblockPreference {
    /** 灰色解锁开关（默认关：解锁有法律风险，PRD Q3 决策）。 */
    suspend fun isUnblockEnabled(): Boolean
}

/** 将 [NeteaseApi] 适配为 [SongUrlApi]。 */
class NeteaseApiSongUrlAdapter(private val api: NeteaseApi) : SongUrlApi {
    override suspend fun songURL(ids: List<Long>, level: String): List<SongURLData> =
        api.songURL(ids, level)
}

/** 将 [SettingsRepository] 适配为 [UnblockPreference]。 */
class SettingsUnblockAdapter(
    private val repository: SettingsRepository,
) : UnblockPreference {
    override suspend fun isUnblockEnabled(): Boolean =
        repository.settings.first().unblockEnabled
}

/** 便捷扩展：把 API 收窄为解析器端口。 */
fun NeteaseApi.asSongUrlApi(): SongUrlApi = NeteaseApiSongUrlAdapter(this)

/** 便捷扩展：把设置仓库收窄为解析器端口。 */
fun SettingsRepository.asUnblockPreference(): UnblockPreference = SettingsUnblockAdapter(this)