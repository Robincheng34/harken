// Ported from kumone by missuo (LGPL-3.0)
//
// 播放记录「本地隐藏」键池（进程级单例）。
//
// 背景：删除播放记录的服务端接口是自编的、官方不认（见 NeteaseApi.deletePlayRecord
// 注释），所以「删除」只能在本地把该条记录隐藏掉。原先用页面级 `remember` 存隐藏键，
// 一旦离开「最近播放」页再进来（路由出栈再入栈 → 可组合项销毁重建）就丢态 → 记录回弹
// （「所有时间」是默认 Tab，最容易复现）。提升到进程级单例后，同一次 App 生命周期内
// 单曲删除 / 清空都不会再回弹：切 Tab、退出重进、甚至整个导航栈重建都保持。
//
// 注意：进程重启后键池清空（记录会重新出现）——因为服务端并未真正删除，这只是
// 本地可见性控制，无法做到跨重启持久（真要持久需落盘，见后续如需）。

package im.missuo.kumone.feature.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object PlayRecordHideStore {
    private val _hiddenKeys = MutableStateFlow<Set<String>>(emptySet())
    val hiddenKeys: StateFlow<Set<String>> = _hiddenKeys.asStateFlow()

    /** 隐藏单条（传入 [im.missuo.kumone.feature.library.pages.playRecordKey] 生成的键）。 */
    fun hide(key: String) {
        _hiddenKeys.update { it + key }
    }

    /** 批量隐藏（清空全部时用）。 */
    fun hideAll(keys: Collection<String>) {
        if (keys.isNotEmpty()) _hiddenKeys.update { it + keys }
    }

    /**
     * 取消隐藏单条（传入 [im.missuo.kumone.feature.library.pages.playRecordKey] 生成的键）。
     *
     * 为什么需要：键是「曲名|歌手|时长」的静态组合，与播放次数无关。一首歌被本地删除
     * （或清空）后，若再播放一次，服务端仍会把它录回 `/v1/play/record`，但键没变 →
     * 依旧被 [hiddenKeys] 挡在列表外，用户观感就是「最近播放不再记录新播放」。
     * 因此起播时（RecentPlaysScreen 对 currentTrack 调用一次）要把对应的键摘掉，
     * 让重新播放的歌重新现身。
     */
    fun unhide(key: String) {
        _hiddenKeys.update { it - key }
    }
}
