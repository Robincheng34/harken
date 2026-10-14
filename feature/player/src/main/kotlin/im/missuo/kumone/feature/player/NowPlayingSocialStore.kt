// Ported from kumone by missuo (LGPL-3.0)
//
// 播放页社交状态（红心/关注歌手）的进程级存储。
//
// 官方播放页的点赞与关注都是「点一下立刻变、失败再回滚」的乐观交互
// （iOS `AccountStore.toggleLike` / `subscribeArtist`）。种子数据
// （`likedTrackIDs` / `likedArtists`）只需拉一次，故收敛为进程级单例：
// 播放页反复开关不重复请求，且与歌单详情等其它入口共享同一份真相。

package im.missuo.kumone.feature.player

import im.missuo.kumone.net.api.NeteaseApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 进程级社交状态：已点赞歌曲 + 已关注歌手。
 *
 * 两个集合都以「null = 未种子化」与「空集 = 种子化后确实为空」区分，
 * 让 UI 在种子加载期间不把「未点赞」渲染成实心状态。
 */
object NowPlayingSocialStore {

    // 进程级 IO scope：seed 不再依赖调用方传入的组合级 scope。
    // 原 bug：seed 挂在 rememberCoroutineScope 上，中途离开播放页协程被取消，
    // 而 seeded 已置 true → 红心/关注状态本进程内永不加载。
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _likedTrackIDs = MutableStateFlow<Set<Long>?>(null)
    val likedTrackIDs: StateFlow<Set<Long>?> = _likedTrackIDs.asStateFlow()

    private val _followingArtistIDs = MutableStateFlow<Set<Long>?>(null)
    val followingArtistIDs: StateFlow<Set<Long>?> = _followingArtistIDs.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    @Volatile
    private var seeded = false

    /**
     * 种子化（进程内仅一次）：当前用户红心歌曲 + 已关注歌手。
     *
     * 使用内部进程级 scope（[storeScope]）发起，不再依赖调用方传入 scope——
     * 组合级 scope 会随页面销毁取消，导致种子加载被中断后永不重试。
     *
     * 任一失败都不阻断另一路；失败的集合保持 null（UI 按未加载处理，
     * 点击时仍可乐观切换并尝试写服务端）。
     */
    fun seed(api: NeteaseApi) {
        if (seeded) return
        seeded = true
        storeScope.launch {
            runCatching { api.userAccount()?.userId }
                .onSuccess { uid ->
                    if (uid != null) {
                        runCatching { api.likedTrackIDs(uid) }
                            .onSuccess { ids -> _likedTrackIDs.value = ids.toSet() }
                            .onFailure { _errors.tryEmit("红心列表加载失败") }
                    } else {
                        _likedTrackIDs.value = emptySet()
                    }
                }
                .onFailure { _errors.tryEmit("用户信息加载失败") }
        }
        storeScope.launch {
            runCatching { api.likedArtists() }
                .onSuccess { artists ->
                    _followingArtistIDs.value = artists.map { it.id }.toSet()
                }
                .onFailure { _errors.tryEmit("关注列表加载失败") }
        }
    }

    /** 点赞 / 取消点赞（乐观更新，失败回滚）。 */
    fun toggleLike(api: NeteaseApi?, scope: CoroutineScope, trackID: Long) {
        val current = _likedTrackIDs.value
        val wasLiked = current?.contains(trackID) == true
        val optimistic = !wasLiked
        // 种子未加载（null）时按空集处理：先乐观落地，服务端确认后不回滚；
        // 种子随后到达会以全量覆盖，最终一致。
        val base = current ?: emptySet()
        _likedTrackIDs.value = if (optimistic) base + trackID else base - trackID
        val apiClient = api ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { apiClient.likeTrack(trackID, optimistic) }
                .onFailure {
                    val rollbackBase = _likedTrackIDs.value ?: base
                    _likedTrackIDs.value =
                        if (wasLiked) rollbackBase + trackID else rollbackBase - trackID
                    _errors.tryEmit("操作失败，请稍后重试")
                }
        }
    }

    /** 关注 / 取消关注歌手（乐观更新，失败回滚）。 */
    fun toggleFollow(api: NeteaseApi?, scope: CoroutineScope, artistID: Long) {
        val current = _followingArtistIDs.value
        val wasFollowing = current?.contains(artistID) == true
        val optimistic = !wasFollowing
        val base = current ?: emptySet()
        _followingArtistIDs.value =
            if (optimistic) base + artistID else base - artistID
        val apiClient = api ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { apiClient.subscribeArtist(artistID, optimistic) }
                .onFailure {
                    val rollbackBase = _followingArtistIDs.value ?: base
                    _followingArtistIDs.value =
                        if (wasFollowing) rollbackBase + artistID else rollbackBase - artistID
                    _errors.tryEmit("操作失败，请稍后重试")
                }
        }
    }
}
