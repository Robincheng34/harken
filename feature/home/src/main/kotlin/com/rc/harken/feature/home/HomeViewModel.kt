// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 首页 ViewModel（T18 `HomeViewModel.kt`，iOS `HomeViewModel` 1:1 移植）。
//
// 并发拉取推荐歌单 / 每日推荐 / 排行榜 / 新碟 / 热门歌手，登录态追加雷达歌单，
// 聚合为 [HomeUiState]。纯 Kotlin + 协程，可纯 JVM 单测（见 `HomeViewModelTest`）。

package com.rc.harken.feature.home

import com.rc.harken.net.api.NeteaseApi
import com.rc.harken.net.api.NeteaseApiError
import com.rc.harken.net.api.userMessage
import com.rc.harken.net.model.AlbumSummary
import com.rc.harken.net.model.ArtistSummary
import com.rc.harken.net.model.ToplistItem
import com.rc.harken.net.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel(
    private val api: NeteaseApi,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val _uiState: MutableStateFlow<HomeUiState> = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()


    /** 加载（登录态变化时由 UI 重新触发；已加载则跳过，iOS `if case .loaded`）。 */
    fun load(loggedIn: Boolean) {
        if (_uiState.value.isLoading.not() && _uiState.value.isLoaded()) return
        scope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val recommend = async {
                runCatching {
                    if (loggedIn) {
                        // 登录态：每日推荐歌单在前，个性化推荐去重补齐（iOS fetchRecommendPlaylists）。
                        val head = api.recommendResource()
                        val tail = api.personalizedPlaylists(limit = 30)
                        val seen = HashSet<Long>()
                        (head + tail).filter { seen.add(it.id) }
                    } else {
                        api.personalizedPlaylists(limit = 30)
                    }
                }.getOrNull() ?: emptyList()
            }
            val daily = async { runCatching { api.dailyRecommendSongs() }.getOrNull() ?: emptyList<Track>() }
            val tops = async { runCatching { api.toplists() }.getOrNull() ?: emptyList<ToplistItem>() }
            val albums = async { runCatching { api.newAlbums(limit = 20) }.getOrNull() ?: emptyList<AlbumSummary>() }
            val artists = async { runCatching { api.topArtists(limit = 12) }.getOrNull() ?: emptyList<ArtistSummary>() }
            val radar = if (loggedIn) {
                async { runCatching { loadRadarPlaylists() }.getOrNull() ?: emptyList<RadarPlaylist>() }
            } else null

            val recommendList = recommend.await()
            val dailyList = daily.await()
            val toplistItems = tops.await().filter { it.id in OFFICIAL_TOPLIST_IDS }
            val albumList = albums.await()
            val artistList = artists.await().shuffled().take(6)

            _uiState.update {
                it.copy(
                    isLoading = false,
                    recommendPlaylists = recommendList,
                    dailySongs = dailyList,
                    dailyFirstCover = dailyList.firstOrNull()?.album?.picUrl,
                    toplists = toplistItems,
                    newAlbums = albumList,
                    artists = artistList,
                    radarPlaylists = radar?.await() ?: emptyList(),
                    error = if (recommendList.isEmpty() && albumList.isEmpty()) "网络连接失败" else null,
                )
            }
        }
    }

    /** 重开（错误态重试）。 */
    fun reload(loggedIn: Boolean) {
        _uiState.update { it.copy(isLoading = true) }
        load(loggedIn)
    }

    /** 下拉刷新：保留当前内容，后台重新拉全量数据（失败静默、不闪骨架）。 */
    fun refresh(loggedIn: Boolean) {
        scope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            // 与 load() 相同的并发拉取逻辑（复用同一组 api 调用，失败分支静默）。
            val recommend = async {
                runCatching {
                    if (loggedIn) {
                        val head = api.recommendResource()
                        val tail = api.personalizedPlaylists(limit = 30)
                        val seen = HashSet<Long>()
                        (head + tail).filter { seen.add(it.id) }
                    } else {
                        api.personalizedPlaylists(limit = 30)
                    }
                }.getOrNull() ?: emptyList()
            }
            val daily = async { runCatching { api.dailyRecommendSongs() }.getOrNull() ?: emptyList<Track>() }
            val tops = async { runCatching { api.toplists() }.getOrNull() ?: emptyList<ToplistItem>() }
            val albums = async { runCatching { api.newAlbums(limit = 20) }.getOrNull() ?: emptyList<AlbumSummary>() }
            val artists = async { runCatching { api.topArtists(limit = 12) }.getOrNull() ?: emptyList<ArtistSummary>() }
            val radar = if (loggedIn) {
                async { runCatching { loadRadarPlaylists() }.getOrNull() ?: emptyList<RadarPlaylist>() }
            } else null

            val recommendList = recommend.await()
            val dailyList = daily.await()
            val toplistItems = tops.await().filter { it.id in OFFICIAL_TOPLIST_IDS }
            val albumList = albums.await()
            val artistList = artists.await().shuffled().take(6)

            _uiState.update {
                it.copy(
                    isRefreshing = false,
                    // 仅当拉取成功（非空）才更新内容；失败保留旧数据（下拉刷新
                    // 不应闪回错误态 / 骨架，用户期望是「数据没变」而非「页面崩了」）。
                    recommendPlaylists = recommendList.ifEmpty { it.recommendPlaylists },
                    dailySongs = dailyList.ifEmpty { it.dailySongs },
                    dailyFirstCover = dailyList.firstOrNull()?.album?.picUrl
                        ?: it.dailyFirstCover,
                    toplists = toplistItems.ifEmpty { it.toplists },
                    newAlbums = albumList.ifEmpty { it.newAlbums },
                    artists = artistList.ifEmpty { it.artists },
                    radarPlaylists = (radar?.await() ?: emptyList()).ifEmpty { it.radarPlaylists },
                    error = null,
                )
            }
        }
    }

    /** 雷达歌单：四个个性化雷达 ID 拉简要信息，标题按「|」拆分（iOS loadRadarPlaylists）。 */
    private suspend fun loadRadarPlaylists(): List<RadarPlaylist> {
        val result = mutableListOf<RadarPlaylist>()
        for (id in RADAR_PLAYLIST_IDS) {
            val brief = runCatching { api.playlistBrief(id) }.getOrNull() ?: continue
            val raw = brief.name.orEmpty()
            if (raw.isBlank()) continue
            val parts = raw.split("|")
            val title = if (parts.size > 1) parts.last() else raw
            val subtitle = if (parts.size > 1) parts.dropLast(1).joinToString("|") else null
            result.add(
                RadarPlaylist(
                    id = id,
                    title = title,
                    subtitle = subtitle,
                    coverURL = brief.coverImgUrl,
                ),
            )
        }
        return result
    }

    private fun HomeUiState.isLoaded(): Boolean =
        recommendPlaylists.isNotEmpty() || toplists.isNotEmpty()

    /** 取消内部协程（页面销毁时由 UI 调用，避免收集器常驻泄漏）。 */
    fun cancel() {
        scope.cancel()
    }

    companion object {
        // ---- 进程级复用（切 Tab 回来不重新拉取）----
        @Volatile
        private var shared: HomeViewModel? = null

        @Volatile
        private var sharedApi: NeteaseApi? = null

        /**
         * 进程级复用工厂。
         *
         * 背景：首页在 `HorizontalPager` 里，切到远处 Tab 时页面会被销毁；原实现
         * `remember { HomeViewModel(api) } + onDispose { cancel() }` 连 VM 一起丢掉，
         * 回首页就重新拉全量数据（用户反馈「从搜索页回首页每次都重新获取」）。
         * 挂到进程级后配合 [load] 的「已加载则跳过」守卫，切 Tab 不再发请求；
         * 需要最新数据用下拉刷新 / [reload]。
         */
        fun obtain(api: NeteaseApi): HomeViewModel {
            shared?.let { if (sharedApi === api) return it }
            return synchronized(this) {
                shared?.let { if (sharedApi === api) return it }
                HomeViewModel(api).also {
                    shared = it
                    sharedApi = api
                }
            }
        }

        /** 官方排行榜 ID（iOS 过滤表）：飙升/新歌/热歌/原创/云音乐欧美榜。 */
        private val OFFICIAL_TOPLIST_IDS = setOf(19_723_756L, 3_779_629L, 2_884_035L, 3_778_678L, 60_198L)

        /** 私人雷达家族（全局歌单 ID，内容按登录账号生成）。 */
        private val RADAR_PLAYLIST_IDS = listOf(3_136_952_023L, 2_829_883_282L, 2_829_816_518L, 2_829_896_389L)
    }
}
