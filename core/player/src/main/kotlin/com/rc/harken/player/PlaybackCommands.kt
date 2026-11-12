// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 播放指令辅助（架构文档 §4.1；iOS 各 View 中的 `playPlaylist` / `playAlbum` 收口）。
//
// 把「取数 → 成队列 → 播放」的重复编排从各 feature UI 收敛到一处：歌单详情页、
// 首页 shelf、精选页都走同一路径（先 `playlistDetail`，tracks 为空时按
// trackIds 批量 `songDetails` 补齐前 500 首，再 `MusicController.play`）。

package com.rc.harken.player

import android.util.Log
import com.rc.harken.net.api.NeteaseApi
import com.rc.harken.net.model.Track
import com.rc.harken.playback.PlayContext
import com.rc.harken.playback.PlaySource
import kotlinx.coroutines.CancellationException

/** 歌单播放编排失败原因（供 UI 提示）。 */
class PlaybackCommandException(message: String) : Exception(message)

/**
 * UI 侧播放指令集合。
 *
 * 由 `:app` 的 [com.rc.harken.di.AppGraph] 装配为进程级单例，经
 * [com.rc.harken.design.composition.LocalPlaybackCommands] 下发各 feature。
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
     * （前 500 首）→ 从 0 开始播放。返回 false 表示取数失败（已由
     * [runCommand] 记日志并下发用户可见提示，调用方无需再提示）。
     */
    suspend fun playPlaylist(id: Long): Boolean {
        val detail = runCommand("playlistDetail($id)") { api.playlistDetail(id) } ?: return false
        var tracks = detail.playlist.tracks
        if (tracks.isEmpty()) {
            val ids = detail.playlist.trackIds.map { it.id }
            tracks = runCommand("songDetails(playlist $id)") { api.songDetails(ids.take(500)) }
                ?.songs ?: emptyList()
        }
        if (tracks.isEmpty()) return false
        play(tracks, PlaySource.PLAYLIST, PlayContext.playlist(id, detail.playlist.name))
        return true
    }

    /** 播放专辑全部歌曲（iOS HomeView.albumCard 内联逻辑）。 */
    suspend fun playAlbum(id: Long, name: String): Boolean {
        val detail = runCommand("album($id)") { api.album(id) } ?: return false
        if (detail.songs.isEmpty()) return false
        play(detail.songs, PlaySource.ALBUM, PlayContext.album(id, name))
        return true
    }

    /** 播放歌手热门歌曲（iOS ArtistDetailView 播放全部）。 */
    suspend fun playArtistHotTracks(id: Long, name: String): Boolean {
        val response = runCommand("artist($id)") { api.artist(id) } ?: return false
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
        openPlayer: Boolean = true,
    ) {
        if (tracks.isEmpty()) return
        val index = tracks.indexOfFirst { it.id == startTrackId }
        // 原 bug：indexOfFirst 返回 -1 时被 coerceAtLeast(0) 钳到 0，找不到目标
        // 曲目会静默改播第 0 首。这里直接不播放，交由调用方决定如何提示。
        if (index < 0) return
        play(tracks, source, context, index, openPlayer)
    }

    /** 播放每日推荐（iOS `Destination.daily`）。 */
    suspend fun playDaily(): Boolean {
        val tracks = runCommand("dailyRecommendSongs") { api.dailyRecommendSongs() } ?: return false
        if (tracks.isEmpty()) return false
        play(tracks, PlaySource.DAILY, PlayContext.daily)
        return true
    }

    /**
     * 心动模式：以 [seedTrackId] 为种子在 [playlistID] 内生成智能列表并播放
     * （iOS `startHeartbeatMode`）。无种子或接口不可用时返回 false。
     */
    suspend fun playHeartbeat(seedTrackId: Long, playlistID: Long): Boolean {
        val tracks = runCommand("intelligenceList($seedTrackId, $playlistID)") {
            api.intelligenceList(seedTrackId, playlistID)
        } ?: return false
        if (tracks.isEmpty()) return false
        play(tracks, PlaySource.PLAYLIST, PlayContext.heartbeat)
        return true
    }

    /**
     * 原样播放一个列表。
     *
     * @param openPlayer 起播后是否自动跳转全屏播放页（默认 true，官方客户端行为）。
     *   false 时仅后台起播、迷你播放条浮现，停留在当前页（如「我的」页近期点歌）。
     */
    fun play(
        tracks: List<Track>,
        source: PlaySource,
        context: PlayContext?,
        startIndex: Int = 0,
        openPlayer: Boolean = true,
    ) {
        // 空列表语义 = 清空队列并停止（PlaybackQueue.load 文档）；tracks 为空时
        // onPlayStarted 不触发，避免用户点了空列表后看到一个「未在播放」的播放页。
        controller.play(tracks, startIndex, context, source)
        if (tracks.isNotEmpty() && openPlayer) onPlayStarted?.invoke()
    }

    // MARK: - 透传命令

    fun togglePlay() = controller.togglePlay()

    fun next() = controller.next()

    fun previous() = controller.previous()

    fun seekTo(positionMs: Long) = controller.seekTo(positionMs)

    fun setRepeatMode(mode: com.rc.harken.playback.RepeatMode) = controller.setRepeatMode(mode)

    fun setShuffle(enabled: Boolean) = controller.setShuffle(enabled)

    fun startFM() = controller.startFM()

    fun fmNext() = controller.fmNext()

    fun fmTrash() = controller.fmTrash()

    // MARK: - 命令收口

    /**
     * 执行一次可能失败的取数命令，统一收口「日志 + 用户可见提示 + 取消语义」。
     *
     * 替换掉原先每个调用点各写一遍的 `runCatching { api.xxx() }.getOrNull()`，
     * 那个写法有两个硬伤：
     * 1. **对用户完全不可见**：失败只返回 false，而多数调用方直接丢弃返回值
     *    （`HomeScreen:127` `scope.launch { commands?.playPlaylist(id) }`、
     *    `ExploreScreen:93` 同款），用户点了歌单/专辑/歌手「播放全部」后
     *    界面毫无反应，也没有任何提示；
     * 2. **吞掉协程取消**：`runCatching` 连 `CancellationException` 一起捕获并
     *    转成 null，页面销毁后已取消的请求仍会跑完并回写结果——既浪费网络，
     *    也可能用过期数据覆盖新状态。
     *
     * @param label 诊断标签（写进日志，真机上据此判断是哪条命令失败）。
     * @param block 实际取数动作。
     * @return 成功返回 [block] 的结果；失败返回 null（调用方按原语义
     *         `?: return false`，**返回值语义未变**）。
     */
    private suspend fun <T> runCommand(label: String, block: suspend () -> T): T? = try {
        block()
    } catch (cancellation: CancellationException) {
        // 取消必须原样上抛（重新抛出同一个实例即可，无需包装）：吞掉会破坏
        // 结构化并发，让已取消的协程继续执行并回写状态。
        throw cancellation
    } catch (error: Exception) {
        Log.w(TAG, "command $label failed", error)
        notifyCommandFailed()
        null
    }

    /** 播放指令失败的统一提示（文案见 `strings.xml` 的 `player_command_failed`）。 */
    private fun notifyCommandFailed() {
        controller.notifyToast(R.string.player_command_failed)
    }

    private companion object {
        const val TAG = "PlaybackCommands"
    }
}
