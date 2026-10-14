// Ported from kumone by missuo (LGPL-3.0)
//
// 播放指令辅助（架构文档 §4.1；iOS 各 View 中的 `playPlaylist` / `playAlbum` 收口）。
//
// 把「取数 → 成队列 → 播放」的重复编排从各 feature UI 收敛到一处：歌单详情页、
// 首页 shelf、精选页都走同一路径（先 `playlistDetail`，tracks 为空时按
// trackIds 批量 `songDetails` 补齐前 500 首，再 `MusicController.play`）。

package im.missuo.kumone.player

import im.missuo.kumone.net.api.NeteaseApi
import im.missuo.kumone.net.model.Track
import im.missuo.kumone.playback.PlayContext
import im.missuo.kumone.playback.PlaySource

/** 歌单播放编排失败原因（供 UI 提示）。 */
class PlaybackCommandException(message: String) : Exception(message)

/**
 * UI 侧播放指令集合。
 *
 * 由 `:app` 的 [im.missuo.kumone.di.AppGraph] 装配为进程级单例，经
 * [im.missuo.kumone.design.composition.LocalPlaybackCommands] 下发各 feature。
 */
class PlaybackCommands(
    private val api: NeteaseApi,
    private val controller: MusicController,
) {

    /**
     * 起播回调（官方客户端行为：点击歌曲即自动跳转全屏播放页）。
     *
     * 由 `:app` 装配后写入（AppContent 内桥到覆盖层 NavController）；
     * 仅在 [play] 真正起播（非空队列）后触发一次。FM（[startFM]）刻意不走
     * 此回调——FM 有独立全屏页，不应被播放页覆盖。
     */
    var onPlayStarted: (() -> Unit)? = null

    /**
     * 播放整个歌单（iOS `playPlaylist`）：拉详情 → tracks 为空按 trackIds 补齐
     * （前 500 首）→ 从 0 开始播放。返回 false 表示取数失败（调用方可静默）。
     */
    suspend fun playPlaylist(id: Long): Boolean {
        val detail = runCatching { api.playlistDetail(id) }.getOrNull() ?: return false
        var tracks = detail.playlist.tracks
        if (tracks.isEmpty()) {
            val ids = detail.playlist.trackIds.map { it.id }
            tracks = runCatching { api.songDetails(ids.take(500)) }.getOrNull()?.songs ?: emptyList()
        }
        if (tracks.isEmpty()) return false
        play(tracks, PlaySource.PLAYLIST, PlayContext.playlist(id, detail.playlist.name))
        return true
    }

    /** 播放专辑全部歌曲（iOS HomeView.albumCard 内联逻辑）。 */
    suspend fun playAlbum(id: Long, name: String): Boolean {
        val detail = runCatching { api.album(id) }.getOrNull() ?: return false
        if (detail.songs.isEmpty()) return false
        play(detail.songs, PlaySource.ALBUM, PlayContext.album(id, name))
        return true
    }

    /** 播放歌手热门歌曲（iOS ArtistDetailView 播放全部）。 */
    suspend fun playArtistHotTracks(id: Long, name: String): Boolean {
        val response = runCatching { api.artist(id) }.getOrNull() ?: return false
        if (response.hotSongs.isEmpty()) return false
        play(response.hotSongs, PlaySource.ARTIST, PlayContext.artist(id, name))
        return true
    }

    /** 从指定曲目开始播放列表（iOS `play(tracks:startAt:)`）。 */
    fun playFrom(
        tracks: List<Track>,
        startTrackId: Long,
        source: PlaySource,
        context: PlayContext?,
    ) {
        if (tracks.isEmpty()) return
        val index = tracks.indexOfFirst { it.id == startTrackId }
        // 原 bug：indexOfFirst 返回 -1 时被 coerceAtLeast(0) 钳到 0，找不到目标
        // 曲目会静默改播第 0 首。这里直接不播放，交由调用方决定如何提示。
        if (index < 0) return
        play(tracks, source, context, index)
    }

    /** 播放每日推荐（iOS `Destination.daily`）。 */
    suspend fun playDaily(): Boolean {
        val tracks = runCatching { api.dailyRecommendSongs() }.getOrNull() ?: return false
        if (tracks.isEmpty()) return false
        play(tracks, PlaySource.DAILY, PlayContext.daily)
        return true
    }

    /**
     * 心动模式：以 [seedTrackId] 为种子在 [playlistID] 内生成智能列表并播放
     * （iOS `startHeartbeatMode`）。无种子或接口不可用时返回 false。
     */
    suspend fun playHeartbeat(seedTrackId: Long, playlistID: Long): Boolean {
        val tracks = runCatching { api.intelligenceList(seedTrackId, playlistID) }
            .getOrNull() ?: return false
        if (tracks.isEmpty()) return false
        play(tracks, PlaySource.PLAYLIST, PlayContext.heartbeat)
        return true
    }

    /** 原样播放一个列表。 */
    fun play(
        tracks: List<Track>,
        source: PlaySource,
        context: PlayContext?,
        startIndex: Int = 0,
    ) {
        if (tracks.isEmpty()) {
            // 空列表语义 = 清空队列并停止（PlaybackQueue.load 文档）；不触发跳转，
            // 避免用户点了空列表后看到一个「未在播放」的播放页。
            controller.play(tracks, startIndex, context, source)
            return
        }
        controller.play(tracks, startIndex, context, source)
        onPlayStarted?.invoke()
    }

    // MARK: - 透传命令

    fun togglePlay() = controller.togglePlay()

    fun next() = controller.next()

    fun previous() = controller.previous()

    fun seekTo(positionMs: Long) = controller.seekTo(positionMs)

    fun setRepeatMode(mode: im.missuo.kumone.playback.RepeatMode) = controller.setRepeatMode(mode)

    fun setShuffle(enabled: Boolean) = controller.setShuffle(enabled)

    fun startFM() = controller.startFM()

    fun fmNext() = controller.fmNext()

    fun fmTrash() = controller.fmTrash()
}
