// Ported from kumone by missuo (LGPL-3.0)
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

package im.missuo.kumone.feature.detail

import im.missuo.kumone.net.api.NeteaseApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DetailViewModel(
    private val api: NeteaseApi,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val _uiState: MutableStateFlow<DetailUiState> = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

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

    fun togglePlaylistSubscription() {
        val state = _uiState.value
        val target = !state.header.subscribed
        scope.launch {
            val ok = runCatching {
                api.subscribePlaylist(state.header.id, target)
            }.isSuccess
            if (ok) {
                _uiState.update {
                    it.copy(
                        header = it.header.copy(
                            subscribed = target,
                            subscribedCount = (it.header.subscribedCount + if (target) 1 else -1)
                                .coerceAtLeast(0),
                        ),
                    )
                }
            }
        }
    }

    fun toggleAlbumSubscription() {
        val id = _uiState.value.header.id
        scope.launch { runCatching { api.subscribeAlbum(id, true) } }
    }

    fun toggleArtistSubscription() {
        val id = _uiState.value.header.id
        scope.launch { runCatching { api.subscribeArtist(id, true) } }
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
