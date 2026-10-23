// Ported from kumone by missuo (LGPL-3.0)
//
// 播放记录「本地隐藏」键池（进程级单例 + 落盘持久化）。
//
// 背景：删除播放记录的服务端接口是自编的、官方不认（见 NeteaseApi.deletePlayRecord
// 注释），所以「删除」只能在本地把该条记录隐藏掉。原先用页面级 `remember` 存隐藏键，
// 一旦离开「最近播放」页再进来（路由出栈再入栈 → 可组合项销毁重建）就丢态 → 记录回弹
// （「所有时间」是默认 Tab，最容易复现）。提升到进程级单例后，同一次 App 生命周期内
// 单曲删除 / 清空都不会再回弹。
//
// 第五轮：用户反馈「删除后重启 APP 又会回来」。根因是键池只在内存里，进程重启即清空。
// 现在经 [attach] 注入 [RecentPlayHideRepository]（DataStore 落盘）：
//  - 冷启动时把落盘的隐藏键并入内存集合；
//  - 每次 hide / hideAll / unhide 都异步回写快照。
// 这样「删除」跨重启保持，重新播放该曲时仍会 unhide 让它重新出现。

package im.missuo.kumone.feature.library

import im.missuo.kumone.storage.playrecord.RecentPlayHideRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

object PlayRecordHideStore {
    private val _hiddenKeys = MutableStateFlow<Set<String>>(emptySet())
    val hiddenKeys: StateFlow<Set<String>> = _hiddenKeys.asStateFlow()

    /** 落盘写入用独立 IO 作用域（不依赖任何界面生命周期，进程内常驻）。 */
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var repository: RecentPlayHideRepository? = null

    /**
     * 冷启动装配（由 AppGraph 调用一次）：注入落盘仓储并载入已删除键。
     *
     * 载入采用**并集**而非覆盖：装配是异步的，期间若用户已删除某条（内存里已有该键），
     * 并集不会把这次删除冲掉。
     */
    fun attach(repository: RecentPlayHideRepository) {
        if (this.repository != null) return
        this.repository = repository
        persistScope.launch {
            val persisted = runCatching { repository.load() }.getOrNull().orEmpty()
            if (persisted.isNotEmpty()) {
                _hiddenKeys.update { it + persisted }
            }
        }
    }

    /** 隐藏单条（传入 [im.missuo.kumone.feature.library.pages.playRecordKey] 生成的键）。 */
    fun hide(key: String) {
        _hiddenKeys.update { it + key }
        persist()
    }

    /** 批量隐藏（清空全部时用）。 */
    fun hideAll(keys: Collection<String>) {
        if (keys.isNotEmpty()) {
            _hiddenKeys.update { it + keys }
            persist()
        }
    }

    /**
     * 取消隐藏单条（传入 [im.missuo.kumone.feature.library.pages.playRecordKey] 生成的键）。
     *
     * 为什么需要：键是「曲名|歌手|时长」的静态组合，与播放次数无关。一首歌被本地删除
     * （或清空）后，若再播放一次，服务端仍会把它录回 `/v1/play/record`，但键没变 →
     * 依旧被 [hiddenKeys] 挡在列表外，用户观感就是「最近播放不再记录新播放」。
     * 因此起播时（RecentPlaysScreen 对 currentTrack 调用一次）要把对应的键摘掉，
     * 让重新播放的歌重新现身；摘除同样要落盘，否则重启后又会被旧快照挡住。
     */
    fun unhide(key: String) {
        _hiddenKeys.update { it - key }
        persist()
    }

    /** 把当前快照回写落盘（fire-and-forget；失败静默，最坏退化为旧的内存语义）。 */
    private fun persist() {
        val repo = repository ?: return
        val snapshot = _hiddenKeys.value
        persistScope.launch {
            runCatching { repo.save(snapshot) }
        }
    }
}
