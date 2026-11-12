// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 详情页 ViewModel（T21/T22 `DetailViewModel.kt`）。
//
// 三类详情共用一个 ViewModel，按 [DetailKind] 分派取数：
//  - 歌单 `playlistDetail` → `tracks`；为空时按 `trackIds` 分批 `songDetails` 补齐
//    （iOS `loadTracks`，每批 500 首，最多 [MAX_TRACK_BATCHES] 批）；
//  - 专辑 `album` → `album.songs`；
//  - 歌手 `artist` → `hotSongs`，另取 `artistAlbums` 供横滑。
// 收藏状态经 `subscribePlaylist` / `subscribeAlbum` / `subscribeArtist` 切换。
// 纯 Kotlin + 协程，可纯 JVM 单测。

package com.rc.harken.feature.detail

import com.rc.harken.net.api.NeteaseApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DetailViewModel(
    private val api: NeteaseApi,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val _uiState: MutableStateFlow<DetailUiState> = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    /**
     * 收藏失败的一次性事件（失败必须有用户可见反馈）。
     *
     * 只发事件不带文案：文案由 Screen 从 `strings.xml` 取；也不复用
     * [DetailUiState.error]——后者是「整页加载失败」的门控，塞进去会把页面
     * 直接切成错误态。
     */
    private val _subscribeFailures = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val subscribeFailures: SharedFlow<Unit> = _subscribeFailures.asSharedFlow()

    private var loadJob: Job? = null

    /** 加载歌单详情（iOS `PlaylistDetailView.load`）。 */
    fun loadPlaylist(id: Long) = load(DetailKind.PLAYLIST, id)

    /** 加载专辑详情（iOS `AlbumDetailView.load`）。 */
    fun loadAlbum(id: Long) = load(DetailKind.ALBUM, id)

    /** 加载歌手详情（iOS `ArtistDetailView.load`）。 */
    fun loadArtist(id: Long) = load(DetailKind.ARTIST, id)

    private fun load(kind: DetailKind, id: Long) {
        loadJob?.cancel()
        loadJob = scope.launch {
            _uiState.update { DetailUiState(kind = kind, isLoading = true) }
            when (kind) {
                DetailKind.PLAYLIST -> loadPlaylistInto(id)
                DetailKind.ALBUM -> loadAlbumInto(id)
                DetailKind.ARTIST -> loadArtistInto(id)
            }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private suspend fun loadPlaylistInto(id: Long) {
        val detail = runCatching { api.playlistDetail(id) }.getOrNull()
        if (detail == null) {
            _uiState.update { it.copy(error = ERROR_LOAD) }
            return
        }
        val playlist = detail.playlist
        val creator = playlist.creator
        _uiState.update {
            it.copy(
                header = DetailHeaderState(
                    id = playlist.id,
                    title = playlist.name,
                    subtitle = if (creator != null) "by ${creator.nickname}" else "",
                    coverURL = playlist.coverImgUrl,
                    description = playlist.description,
                    creatorName = creator?.nickname.orEmpty(),
                    creatorAvatar = creator?.avatarUrl,
                    playCount = playlist.playCount,
                    trackCount = playlist.trackCount,
                    subscribed = playlist.subscribed,
                    subscribedCount = playlist.subscribedCount,
                ),
                tracks = playlist.tracks,
                tracksComplete = playlist.tracks.isNotEmpty(),
                totalTrackCount = playlist.trackCount,
            )
        }
        // tracks 为空（超长歌单）时按 trackIds 分批补齐。
        if (playlist.tracks.isEmpty() && playlist.trackIds.isNotEmpty()) {
            loadTrackBatches(playlist.trackIds.map { it.id })
        }
    }

    /** 分批补齐曲目（iOS `loadTracks`：每批 500 首，逐批追加）。 */
    private suspend fun loadTrackBatches(ids: List<Long>) {
        var loaded = 0
        ids.chunked(TRACK_BATCH_SIZE).take(MAX_TRACK_BATCHES).forEach { batch ->
            val songs = runCatching { api.songDetails(batch) }.getOrNull()?.songs ?: return
            loaded += songs.size
            _uiState.update { it.copy(tracks = it.tracks + songs) }
        }
        _uiState.update { it.copy(tracksComplete = true, totalTrackCount = maxOf(it.totalTrackCount, loaded)) }
    }

    private suspend fun loadAlbumInto(id: Long) {
        val detail = runCatching { api.album(id) }.getOrNull()
        if (detail == null) {
            _uiState.update { it.copy(error = ERROR_LOAD) }
            return
        }
        val album = detail.album
        _uiState.update {
            it.copy(
                header = DetailHeaderState(
                    id = album.id,
                    title = album.name,
                    subtitle = album.artist?.name.orEmpty(),
                    coverURL = album.picUrl,
                    description = album.description,
                    creatorName = album.artist?.name.orEmpty(),
                    creatorAvatar = album.artist?.picUrl,
                    trackCount = album.size,
                ),
                tracks = detail.songs,
                tracksComplete = true,
                totalTrackCount = album.size,
            )
        }
    }

    private suspend fun loadArtistInto(id: Long) {
        val response = runCatching { api.artist(id) }.getOrNull()
        if (response == null) {
            _uiState.update { it.copy(error = ERROR_LOAD) }
            return
        }
        val artist = response.artist
        val albums = runCatching { api.artistAlbums(id) }.getOrNull() ?: emptyList()
        _uiState.update {
            it.copy(
                header = DetailHeaderState(
                    id = artist.id,
                    title = artist.name,
                    subtitle = artist.alias.firstOrNull().orEmpty(),
                    coverURL = artist.picUrl,
                    description = artist.briefDesc,
                    creatorName = artist.name,
                    creatorAvatar = artist.picUrl,
                    trackCount = artist.musicSize,
                    // 歌手接口回传 `followed`：必须作为收藏按钮的真值，
                    // 否则「已关注」的歌手永远渲染成未关注。
                    subscribed = artist.followed,
                ),
                tracks = response.hotSongs,
                artistAlbums = albums,
                artist = artist,
                tracksComplete = true,
                totalTrackCount = artist.musicSize,
            )
        }
    }

    // MARK: - 收藏切换（iOS 各详情页 `.subscribe` 按钮）

    /**
     * 歌单收藏 / 取消收藏。
     *
     * 原 bug：只发 `subscribe(true)` 单向请求且成功才更新 `header.subscribed`，
     * 大部分失败路径下按钮点完毫无变化（无提示）。现统一走
     * [toggleSubscription]：乐观更新 + 失败回滚 + 失败事件。
     */
    fun togglePlaylistSubscription() {
        toggleSubscription(
            request = { id, target -> api.subscribePlaylist(id, target) },
            adjustCount = true,
        )
    }

    /** 专辑收藏 / 取消收藏（`/album/sub` / `/album/unsub`）。 */
    fun toggleAlbumSubscription() {
        toggleSubscription(
            request = { id, target -> api.subscribeAlbum(id, target) },
            adjustCount = false,
        )
    }

    /** 歌手关注 / 取消关注（`/artist/sub` / `/artist/unsub`）。 */
    fun toggleArtistSubscription() {
        toggleSubscription(
            request = { id, target -> api.subscribeArtist(id, target) },
            adjustCount = false,
        )
    }

    /**
     * 收藏态切换（乐观更新 + 失败回滚）。
     *
     * @param request 实际请求（`target = true` 收藏 / `false` 取消）。
     * @param adjustCount 是否联动收藏数（仅歌单接口回传收藏数）。
     *
     * 成功判定：接口无返回值，按「无异常即成功」；成功后以服务端为准的语义由
     * 乐观值保持（服务端不回传新状态），失败则回到点击前的完整 header 快照。
     */
    private fun toggleSubscription(
        request: suspend (Long, Boolean) -> Unit,
        adjustCount: Boolean,
    ) {
        val state = _uiState.value
        val id = state.header.id
        // 详情还没加载出来（id == 0）时没有可收藏对象，忽略。
        if (id == 0L) return
        // 请求进行中忽略重复点击，避免两次乐观翻转互相覆盖。
        if (state.isSubscribing) return

        val target = !state.header.subscribed
        val original = state.header
        _uiState.update {
            it.copy(isSubscribing = true, header = it.header.withSubscription(target, adjustCount))
        }
        scope.launch {
            runCatching { request(id, target) }
                .onSuccess {
                    _uiState.update { it.copy(isSubscribing = false) }
                }
                .onFailure {
                    _uiState.update { current ->
                        // 期间可能已切到别的详情：只在同一对象上回滚。
                        if (current.header.id == id) {
                            current.copy(isSubscribing = false, header = original)
                        } else {
                            current.copy(isSubscribing = false)
                        }
                    }
                    _subscribeFailures.tryEmit(Unit)
                }
        }
    }

    /** 取消内部协程（页面销毁时由 UI 调用，避免收集器常驻泄漏）。 */
    fun cancel() {
        scope.cancel()
    }

    private companion object {
        const val ERROR_LOAD = "加载失败"
        const val TRACK_BATCH_SIZE = 500
        const val MAX_TRACK_BATCHES = 4
    }
}
