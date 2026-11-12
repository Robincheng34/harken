// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.rc.harken.net.api.NeteaseApi
import com.rc.harken.net.lyrics.LyricsParser
import com.rc.harken.net.model.Track
import com.rc.harken.playback.AudioQuality
import com.rc.harken.playback.PlayContext
import com.rc.harken.playback.PlaySource
import com.rc.harken.playback.PlaybackQueue
import com.rc.harken.playback.RepeatMode
import com.rc.harken.playback.isStablePlayback
import com.rc.harken.player.unblock.NeteaseUnblockSource
import com.rc.harken.storage.download.LocalSongSource
import com.rc.harken.storage.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ExoPlayer 播放引擎（iOS `PlayerService` 的引擎部分）。
 *
 * 职责：驱动 [com.rc.harken.playback.PlaybackQueue] 状态机、解析播放地址
 * （音质回退）、维护 [PlayerState] 状态流、切歌不断播、上报。UI 侧不直接接触
 * ExoPlayer，一切经 [MusicController]。
 *
 * 进程内单例（[acquire]）：单 Activity 应用，PlayerService 与 MusicController
 * 共享同一实例——服务负责 MediaSession/通知外壳，引擎负责播放本体。
 *
 * 线程：所有命令经内部 [scope]（调用方提供，Main）串行执行；ExoPlayer 回调
 * 亦在主线程。
 */
class PlaybackEngine(
    private val context: Context,
    private val api: NeteaseApi,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val localSourceProvider: LocalSongSource? = null,
) {

    /** ExoPlayer 实例；PlayerService 用它挂载 MediaSession。 */
    internal val exoPlayer: ExoPlayer = ExoPlayer.Builder(context)
        // 媒体下载统一带 Referer + 浏览器 UA：B 站部分 CDN（upos-sz-mirrorcos /
        // cn-jsnt-ct 等）有防盗链，缺 Referer 会返回 403，表现正好是「能显示封面
        // 但不播、随即被引擎当作失败跳下一首」。网易云直链是签名 URL，不受影响。
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                DefaultDataSource.Factory(
                    context,
                    DefaultHttpDataSource.Factory()
                        .setDefaultRequestProperties(
                            // B 站 CDN 的防盗链校验：除 Referer 外还看 Origin / UA。
                            // 实测缺 Referer 或 UA 为移动端/ExoPlayer 时返回 403；
                            // 桌面 Chrome UA + 带斜杠的 Referer + Origin 可稳定 206。
                            mapOf(
                                "Referer" to "https://www.bilibili.com/",
                                "Origin" to "https://www.bilibili.com",
                                "Accept" to "*/*",
                                "Accept-Language" to "zh-CN,zh;q=0.9",
                                "User-Agent" to PLAYER_UA,
                            ),
                        ),
                ),
            ),
        )
        // 音频焦点三件套（原实现全缺）：①申请焦点——来电话/导航播报/其他 App
        // 起播时自动暂停本 App，焦点归还后按 ExoPlayer 默认策略恢复；②拔耳机/
        // 断蓝牙（AUDIO_BECOMING_NOISY）自动暂停，不外放；③播放期持有
        // WAKE_LOCK（需 manifest WAKE_LOCK 权限），息屏流式不断流。
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .build()

    /** 灰色解锁自建 client（连接 15s / 读取 30s）；注入 [SongUrlResolver] 作救援源。 */
    private val unblockClient = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val resolver = SongUrlResolver(
        api,
        settings,
        unblock = NeteaseUnblockSource(unblockClient),
        localSourceProvider = localSourceProvider,
    )
    private val scrobbler = Scrobbler(api)
    private val queue = PlaybackQueue()

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    /** 解析代际：快速切歌时丢弃过期的异步结果（iOS `resolveGeneration`）。 */
    private var playGeneration = 0

    /**
     * FM 拉取代际：快速连点「漫游」或与普通播放竞态时，丢弃过期的整批拉取结果
     * （模式同 [playGeneration]），防止多个 `loadFMTracks` 互相覆盖队列。
     */
    private var fmGeneration = 0

    private var consecutiveFailures = 0
    private var preferredQuality: AudioQuality = AudioQuality.EXHIGH

    /**
     * 用户暂停意图，用于跨过解析空档期。
     *
     * 切歌后 `deckLoaded=false` 期间旧 deck 仍在播：此时用户按「暂停」暂停的是
     * 旧 deck，若 resolve 完成后无条件 `exoPlayer.play()`，用户的暂停会被覆盖
     * （解析越慢窗口越长）。记录意图后，装填新曲时不再自动起播；用户显式发起
     * 播放/切歌时复位。
     */
    private var userPauseIntent = false

    /**
     * 听歌上报的结算槽（iOS `scrobbleIfNeeded` 的 `scrobbled` + 快照 sourceID）。
     *
     * 见 [ScrobbleLedger]：结算发生在**切歌瞬间**，此时队列已是新歌单，必须用在
     * 开播时快照下来的 sourceID，否则上一首的收听时长会记到下一首的歌单头上。
     */
    private val scrobbleLedger = ScrobbleLedger()

    /**
     * 新曲是否已真正装填进播放器（iOS `deckLoaded`，PlayerService.swift:934）。
     *
     * 解析期间旧曲仍在播——iOS 刻意不在拿到 URL 前停掉活动 deck，以免出现空档。
     * 但此时绝不能把旧曲的播放头读成新曲进度：iOS 曾因此在 200ms 内被 tick 覆写
     * 刚清零的 `progress`（见 :935-948 注释）。此标志即 iOS 用来阻断该覆写的开关。
     */
    private var deckLoaded = false

    /**
     * 前台服务（[PlayerService]）是否已启动。
     *
     * 与引擎同为进程内单例语义；服务被停止（onTaskRemoved / 系统回收）时经
     * [onServiceDestroyed] 复位，保证下次播放能重新拉起通知入口。
     */
    private var serviceStarted = false

    /**
     * 引擎是否已释放（[release] 置位）。
     *
     * 原 bug：release 之后 500ms 轮询与 publish 仍会访问已释放的 ExoPlayer，
     * 抛 IllegalStateException。置位后轮询与 publish 先检查短路。
     */
    @Volatile
    private var released = false

    init {
        exoPlayer.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    publish()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) onTrackEnded()
                    publish()
                }

                override fun onPlayerError(error: PlaybackException) {
                    _state.update { current ->
                        current.copy(error = error.localizedMessage ?: "播放失败")
                    }
                    // fromPlayerError=true：报错后 ExoPlayer 必为 STATE_IDLE，
                    // 不能用 isPlaying 判定是否推进（见 advanceAfterFailure 注释）。
                    scope.launch { advanceAfterFailure(fromPlayerError = true) }
                }
            },
        )

        scope.launch {
            settings.settings.collect { appSettings ->
                preferredQuality = appSettings.audioQuality
            }
        }

        // Position polling (iOS progress timer); only while playing.
        scope.launch {
            while (isActive) {
                // iOS PlayerService.swift:935-948：解析未完成时旧曲仍在播，它的
                // 播放头不是本曲进度；`deckLoaded` 为 false 期间不读播放头。
                // `released` 短路：release 后 ExoPlayer 已释放，再读会抛 ISE。
                if (!released && deckLoaded && exoPlayer.isPlaying) {
                    _state.update { it.copy(positionMs = exoPlayer.currentPosition.coerceAtLeast(0)) }
                }
                delay(POSITION_POLL_MS)
            }
        }
    }

    // MARK: - Commands

    /** 加载队列并从 [startIndex] 开始播放（iOS `play(tracks:source:startAt:context:)`）。 */
    fun play(tracks: List<Track>, startIndex: Int, context: PlayContext?, source: PlaySource) {
        // 用户显式播放非 FM 内容：作废在途的 FM 拉取，防止竞态下整批覆盖新队列。
        fmGeneration++
        userPauseIntent = false
        queue.load(tracks, startIndex, context, source)
        consecutiveFailures = 0
        publish()
        playCurrent()
    }

    fun togglePlay() {
        val current = queue.current() ?: return
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
            // 解析空档期（deckLoaded=false）的暂停：旧 deck 被停的是它，意图必须
            // 记下，否则 resolve 完成的 play() 会覆盖用户的暂停。
            if (!deckLoaded) userPauseIntent = true
        } else {
            userPauseIntent = false
            if (exoPlayer.playbackState == Player.STATE_ENDED) exoPlayer.seekTo(0)
            exoPlayer.play()
        }
        publish()
    }

    /**
     * 清空当前播放队列并停止播放（播放队列面板「删除当前播放队列」）。
     *
     * 复用 [PlaybackQueue.load] 的空列表语义清空状态机（该语义见其文档：
     * 空列表即清空并停止），同时停掉并清空 ExoPlayer 的 MediaItem，避免残留后台播放。
     */
    fun clearQueue() {
        // 原 bug：清空队列不递增 playGeneration，在途的 URL 解析协程仍能通过
        // playCurrent 内的 `generation != playGeneration` 检查，随后
        // setMediaItem+prepare+play() 在空队列上「复活」播放器。这里同步作废
        // 解析协程与在途 FM 拉取，并复位 deckLoaded。
        playGeneration++
        fmGeneration++
        userPauseIntent = false
        deckLoaded = false
        exoPlayer.pause()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        queue.load(emptyList(), 0, null, PlaySource.NONE)
        consecutiveFailures = 0
        // deckLoaded 已复位，publish 不再读播放器字段；先把播放态字段清零，
        // 避免 UI 残留「正在播放」。
        _state.update {
            it.copy(isPlaying = false, isBuffering = false, positionMs = 0, durationMs = 0)
        }
        publish()
    }

    /**
     * 从播放队列移除指定位置（队列面板左滑删除）。
     *
     * 只改队列数据，不动当前播放：被删项一定在当前曲之后（面板只列 upcoming），
     * 因此 ExoPlayer 的当前 MediaItem 无需重建。
     */
    fun removeFromQueue(index: Int) {
        queue.removeAt(index)
        publish()
    }

    /** 队列面板长按拖拽排序：把曲目从 from 移到 to（官方 ItemTouchHelper 语义）。 */
    fun moveInQueue(from: Int, to: Int) {
        queue.move(from, to)
        publish()
    }

    fun next() {
        // 用户显式切歌：清除解析空档期记录的暂停意图。
        userPauseIntent = false
        if (queue.isFMMode) {
            fmNext()
            return
        }
        val next = queue.advance()
        if (next == null) {
            exoPlayer.pause()
            exoPlayer.seekTo(0)
            haltStopped()
            return
        }
        playCurrent()
    }

    /**
     * 上一首；播放超过 4 秒时回到本曲开头（iOS `previous()` 语义）。
     * FM 模式下 [PlaybackQueue.previous] 返回 null，同样重启当前曲。
     */
    fun previous() {
        userPauseIntent = false
        // deckLoaded=false（切歌空档）时播放头属于旧曲，不能作为「回到本曲开头」
        // 的判据——否则会把新曲的切换丢掉、把旧曲 seek 回 0（与 500ms 轮询同因）。
        if (deckLoaded && exoPlayer.currentPosition > RESTART_THRESHOLD_MS) {
            exoPlayer.seekTo(0)
            return
        }
        val previous = queue.previous()
        if (previous == null) {
            // 空档期旧 deck 仍在发声，seek(0) 会让旧曲从 0 重播直到新曲装填。
            if (deckLoaded) exoPlayer.seekTo(0)
            return
        }
        playCurrent()
    }

    fun seekTo(positionMs: Long) {
        // 解析空档期忽略拖动：播放头属于旧曲，而拖动的目标位置是新曲的——
        // 落到旧 deck 上会随 setMediaItem 被覆盖，松手后进度条回跳。
        if (!deckLoaded) return
        exoPlayer.seekTo(positionMs.coerceAtLeast(0))
        _state.update { it.copy(positionMs = positionMs.coerceAtLeast(0)) }
    }

    fun setRepeatMode(mode: RepeatMode) {
        queue.repeatMode = mode
        publish()
    }

    fun setShuffle(enabled: Boolean) {
        queue.shuffleEnabled = enabled
        publish()
    }

    /** 私人漫游：拉一批 FM 歌曲并播放。 */
    fun startFM() {
        // 用户显式发起播放：清除解析空档期记录的暂停意图（否则空档期暂停后
        // 点漫游，新队列装填完成会被旧意图压住不起播，表现为「漫游按钮坏了」）。
        userPauseIntent = false
        // 原 bug：快速连点并发发起多个 fetchFM()，各自 loadFMTracks 整批替换
        // 互相覆盖。加代际计数（同 playGeneration 模式），过期结果直接丢弃。
        val generation = ++fmGeneration
        scope.launch {
            val tracks = fetchFM()
            if (generation != fmGeneration) return@launch
            if (tracks.isNullOrEmpty()) {
                _state.update { it.copy(error = "无法获取私人漫游歌曲") }
                return@launch
            }
            loadFMTracks(tracks)
        }
    }

    /** 漫游下一首：批次内还有未播曲目时直接推进，耗尽才续拉。 */
    fun fmNext() {
        // 原 bug：每次都重新 fetch 整批并 loadFMTracks 覆盖队列；且不能用
        // queue.advance()——FM 模式下它恒返回 null（PlaybackQueue 语义：FM 由
        // 上层取歌）。改为：批次内还有未播曲目时 jumpTo 直接推进下一首；
        // 耗尽才带代际续拉整批。
        // 用户显式切歌：清除解析空档期记录的暂停意图（onTrackEnded 自动链路
        // 到不了这里带暂停的状态，复位无副作用）。
        userPauseIntent = false
        if (queue.currentIndex + 1 < queue.size) {
            queue.jumpTo(queue.currentIndex + 1)
            playCurrent()
            return
        }
        val generation = ++fmGeneration
        scope.launch {
            val tracks = fetchFM()
            if (generation != fmGeneration) return@launch
            if (tracks.isNullOrEmpty()) {
                exoPlayer.pause()
                haltStopped("漫游已结束")
                return@launch
            }
            loadFMTracks(tracks)
        }
    }

    /** 垃圾桶：先切下一首，再上报「不喜欢」（架构文档 §5.2）。 */
    fun fmTrash() {
        val current = queue.current() ?: return
        fmNext()
        scope.launch { fetchFMTrash(current) }
    }

    fun release() {
        // 原 bug：release 后 500ms 轮询与 publish 仍会访问已释放的 ExoPlayer，
        // 抛 IllegalStateException。置位短路；同时作废在途的解析/FM 拉取协程，
        // 让它们在代际检查处提前退出，不再触碰播放器。
        released = true
        playGeneration++
        fmGeneration++
        exoPlayer.release()
    }

    // MARK: - Internals

    private fun loadFMTracks(tracks: List<Track>) {
        queue.load(tracks, 0, PlayContext.fm, PlaySource.NONE)
        queue.isFMMode = true
        consecutiveFailures = 0
        publish()
        playCurrent()
    }

    private suspend fun fetchFM(): List<Track>? = try {
        api.personalFM()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        null
    }

    private suspend fun fetchFMTrash(track: Track) {
        try {
            api.fmTrash(track.id)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            // Best-effort.
        }
    }

    /** 解析并播放当前曲；异步结果按代际丢弃，快速切歌不会插播旧曲。 */
    private fun playCurrent() {
        val track = queue.current()
        if (track == null) {
            publish()
            return
        }
        // iOS PlayerService.swift:960 `scrobbleIfNeeded(completed: false)`：
        // 位置在 :961 `currentTrack = track` **之前**，即切歌瞬间先把上一首的收听
        // 时长结算掉。手动点下一曲/上一曲时若不做这一步，上一首的 scrobble 会整笔
        // 丢失（原来只有播完自然结束时 onTrackEnded 才结算）。此时 ledger 里存的
        // 还是上一首的曲目与**它自己的** sourceID 快照，两者都正确。
        finishScrobble()
        ensureServiceStarted()
        val generation = ++playGeneration
        // iOS PlayerService.swift:960-972：切歌瞬间一次性重置「上一首遗留」的
        // 字段，而不是等解析成功后才覆盖。否则试听标记 / 音质档位 / 报错文案 /
        // 旧歌词会跨曲污染（isTrial 残留会让新曲莫名弹试听提示，且 reset 之前
        // `handleResolveFailure` 写入的 error 会一直挂着）。
        deckLoaded = false
        _state.update { current ->
            current.copy(
                currentTrack = track,
                positionMs = 0,
                durationMs = track.durationMS,
                servedQuality = null,
                isTrial = false,
                // iOS :965 `unblockSource = nil`：解锁标记同样不能跨曲残留，
                // 否则新曲走官方源却仍挂着「已使用第三方音源」。
                unblockSource = null,
                lyrics = null,
                error = null,
                isPlaying = true,
            )
        }
        scope.launch {
            val resolved = try {
                resolver.resolve(track, preferredQuality)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                null
            }
            if (generation != playGeneration) return@launch
            if (resolved == null) {
                handleResolveFailure(track)
                return@launch
            }
            // 注意：这里**不能**把 consecutiveFailures 清零。原实现解析一成功就
            // 重置计数，导致「解析成功但播放即挂/即完」的坏源每首都把熔断计数
            // 清零，MAX_CONSECUTIVE_FAILURES 永远触发不了——实测整个 137 首的
            // 队列在几分钟内被自动刷穿循环（播放列表乱跳的根因）。
            // 计数器的清零改由「曲目稳定播放过」判定（见 advanceAfterFailure /
            // onTrackEnded 里的 STABLE_PLAY_MS 检查）。
            exoPlayer.setMediaItem(buildMediaItem(track, resolved))
            exoPlayer.prepare()
            if (userPauseIntent) {
                // 用户在解析空档按了暂停：装填新曲但不起播（尊重暂停意图，
                // 否则解析越慢越明显的「暂停被顶掉」）。
                exoPlayer.pause()
            } else {
                exoPlayer.play()
            }
            deckLoaded = true
            _state.update { current ->
                current.copy(
                    servedQuality = resolved.level,
                    isTrial = resolved.isTrial,
                    // iOS :1093 `unblockSource = resolved.unblockSource`：解锁命中
                    // 时把源名写进状态，UI 据此提示用户。
                    unblockSource = resolved.unblockSource,
                    error = null,
                )
            }
            publish()
            loadLyrics(generation, track.id)
            // iOS :3091 `let sid = source.sourceID`：开播瞬间快照 sourceID，
            // 供切歌时结算使用（不能在结算时再读当前 source）。
            scrobbleLedger.start(track.id, queue.source.sourceID)
            scrobbler.start(track.id, queue.source.sourceID)
        }
    }

    private fun buildMediaItem(track: Track, resolved: ResolvedSongUrl): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(resolved.url)
            .setMediaId(track.id.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.name)
                    .setArtist(track.artistNames)
                    .setAlbumTitle(track.album.name)
                    .setArtworkUri(track.album.picUrl?.let(Uri::parse))
                    // 系统 MediaStyle 通知的进度条需要 METADATA_KEY_DURATION：
                    // media3 的 LegacyConversions 在 ExoPlayer 实际时长未知
                    // （C.TIME_UNSET，即 prepare/解析完成之前）时回退到 metadata
                    // 里声明的时长，再写入 MediaMetadataCompat 供 SystemUI 绘制。
                    // 原先没有时长，通知出现时画不出进度条；这里补曲目时长。
                    // setDurationMs 只接受非负 Long，0 或负值视为「未知」不写入。
                    .setDurationMs(track.durationMS.takeIf { it > 0L })
                    .build(),
            )
        // B 站音轨是 .m4s（URL 无媒体扩展名、Content-Type=octet-stream），显式声明
        // MP4 容器，避免 DefaultMediaSourceFactory 选错提取器后报错、被引擎当作失败跳过。
        if (resolved.unblockSource == "哔哩哔哩") {
            builder.setMimeType(MimeTypes.AUDIO_MP4)
        }
        return builder.build()
    }

    private fun loadLyrics(generation: Int, trackID: Long) {
        scope.launch {
            val response = try {
                api.lyric(trackID)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                return@launch
            }
            if (generation != playGeneration) return@launch
            _state.update { it.copy(lyrics = LyricsParser.parse(response)) }
        }
    }

    private fun onTrackEnded() {
        finishScrobble()
        if (queue.isFMMode) {
            // 原 bug：自然播完走 queue.advance()，而 FM 模式下 advance() 恒返回
            // null（PlaybackQueue 语义：FM 由上层取歌），FM 一首播完即停摆。
            // 复用 fmNext()：批次内优先切下一首，耗尽再续拉。
            fmNext()
            return
        }
        if (queue.repeatMode == RepeatMode.ONE) {
            // iOS PlayerService.swift:869-882：repeat-one 走 `startPlaying(track,
            // indexUnchanged: true)` **重新解析**（注释原文「A drained stream can't
            // restart in place; re-resolve」），因此会再次命中 freeTrialInfo 并重弹
            // 试听提示。此处 seekTo(0) 复用已解析的 URL，不会重新解析——这是与 iOS
            // 的已知差异（ExoPlayer 无法原地重启已耗尽的流），不影响 isTrial 语义。
            exoPlayer.seekTo(0)
            exoPlayer.play()
            // 原 bug：repeat-one 重播不重开 scrobbleLedger——上面的
            // finishScrobble() 已把槽位清空，后续 take() 永远为 null，循环收听
            // 时长整段丢失。重播即重新记账（曲目与歌单不变）。
            queue.current()?.let { current ->
                scrobbleLedger.start(current.id, queue.source.sourceID)
                // scrobbler.start 为挂起函数（网络上报），onTrackEnded 非挂起上下文，
                // 与 :556 的 finish 一致走 scope.launch。
                scope.launch { scrobbler.start(current.id, queue.source.sourceID) }
            }
            return
        }
        // 熔断计数：上一曲「稳定播放过」才算正常播完并重置计数；几乎没播就
        // 「自然结束」视为一次失败。原实现此路径完全没有失败计数——秒结束的
        // 坏音源会一路 advance 把整个队列刷穿并无限循环
        // （实测 137 首队列几分钟内被刷完，播放列表乱跳的根因之一）。
        // 稳定判定见 [isStablePlayback]：短曲完整播完也算成功，否则含 intro/
        // skit 的专辑播到短曲处会被误判停摆。
        val endedDuration = queue.current()?.durationMS ?: 0L
        if (deckLoaded && isStablePlayback(exoPlayer.currentPosition, endedDuration, STABLE_PLAY_MS)) {
            consecutiveFailures = 0
        } else {
            consecutiveFailures += 1
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                // 连续秒完/失败达到上限：停下等用户手动处理，不再自动跳。
                exoPlayer.pause()
                publish()
                return
            }
        }
        // 试听片段播完**照常**进下一曲：iOS 的播放结束链路
        // `deckFinished`(:1180) → `handleItemEnded()`(:849) → `advanceToNext(userInitiated: false)`(:814)
        // 全链路上 isTrial 出现 0 次。`autoAdvanceTarget()` 的 `guard !isTrial`
        // (:1289) 只服务预取/预载管线（唯一调用点 :1318 schedulePrefetch 与 :1366
        // 预取自校验），与播放结束无关。
        val next = queue.advance()
        if (next == null) {
            publish()
            return
        }
        playCurrent()
    }

    /**
     * 失败后的推进策略。
     *
     * 原 bug：第一道闸门 `if (!exoPlayer.isPlaying) { publish(); return }`——
     * ExoPlayer 报错后必为 STATE_IDLE（isPlaying=false），onPlayerError 路径
     * 100% 提前返回，[MAX_CONSECUTIVE_FAILURES] 限流与 [queue.advance] 成死代码。
     *
     * 新判据：「本次失败是否来自当前 deck 加载/播放」：
     * - 解析失败（[fromPlayerError]=false）：失败的就是队列当前曲的 URL 解析，
     *   照常计数与推进；
     * - 播放器报错（[fromPlayerError]=true）：仅当失败的是已装填的 deck
     *   （[deckLoaded]=true）才推进。[deckLoaded]=false 说明报错来自切歌空档期
     *   里仍在播的旧 deck（新曲解析中）——此时绝不能动队列，否则会跳过正在
     *   解析的新曲；静默发布即可。
     */
    private suspend fun advanceAfterFailure(fromPlayerError: Boolean = false) {
        if (released) return
        if (fromPlayerError && !deckLoaded) {
            publish()
            return
        }
        if (queue.current() == null) {
            exoPlayer.pause()
            haltStopped()
            return
        }
        // 上一曲稳定播放过（判定同 onTrackEnded，见 [isStablePlayback]）则视为
        // 一次「成功」重置计数：失败熔断只针对「连续秒挂」的坏源段，不该被长
        // 播放后的偶发一次报错连带。
        // （playCurrent 解析成功处不再清零——否则「解析成功即挂」的坏源会
        // 每首都重置计数，熔断永远触发不了。）
        val currentDuration = queue.current()?.durationMS ?: 0L
        if (deckLoaded && isStablePlayback(exoPlayer.currentPosition, currentDuration, STABLE_PLAY_MS)) {
            consecutiveFailures = 0
        }
        consecutiveFailures += 1
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            // 连续失败达到上限：停下并保留 state.error（onPlayerError 已写入）。
            exoPlayer.pause()
            haltStopped()
            return
        }
        if (queue.isFMMode) {
            // FM 模式下 queue.advance() 恒返回 null（上层取歌语义）：播放器报错
            // 同样要用 fmNext 续播，否则 FM 一报错就停摆（同 onTrackEnded 的修复）。
            // 连败上限闸门在上方已先检查，不会形成无限报错循环。
            fmNext()
            return
        }
        val next = queue.advance()
        if (next == null) {
            exoPlayer.pause()
            haltStopped()
            return
        }
        playCurrent()
    }

    /** 解析失败（无地址/全部回退失败）：报错、按 iOS 策略续跳或停止。
     *  计数统一由 [advanceAfterFailure] 负责（原实现两处各 +1，一次解析失败
     *  被计成两 failures，熔断阈值实际是 MAX 的一半）。 */
    private suspend fun handleResolveFailure(track: Track) {
        val reason = track.playability(
            privilege = null,
            isLoggedIn = false,
            vipType = 0,
        ).reason
        _state.update { current ->
            current.copy(error = "《${track.name}》无法播放" + (reason?.let { "：$it" } ?: ""))
        }
        advanceAfterFailure()
    }

    /**
     * 结算上一首的收听时长（iOS `:3095` `scrobbleIfNeeded`）。
     *
     * 上报用的 sourceID 来自 [ScrobbleLedger] 在开播那一刻的快照，**不是**当前的
     * [PlaybackQueue.source]——调用点（切歌瞬间）队列已经切到新歌单了。
     */
    private fun finishScrobble() {
        // 原实现在 ScrobbleLedger.take() 内用挂钟差值计时，暂停时长也被计入
        // 听歌上报。现改为由调用方传入实际已播秒数（ExoPlayer 播放头换算）。
        // deckLoaded=false（切歌空档/从未装载）时读不到有效播放头，按 0 秒处理
        // 即不结算。注意 playCurrent 在复位 deckLoaded **之前**调用本方法，
        // 此时旧 deck 仍在播，播放头正是上一首的进度。
        val playedSeconds = if (deckLoaded) {
            (exoPlayer.currentPosition / 1_000L).toInt()
        } else {
            0
        }
        val pending = scrobbleLedger.take(playedSeconds) ?: return
        scope.launch { scrobbler.finish(pending.trackID, pending.sourceID, pending.seconds) }
    }

    /**
     * 确保播放前台服务已启动（通知栏 / 锁屏 / 蓝牙控制入口）。
     *
     * 幂等：服务是进程级单例语义（与引擎同生命周期），一旦拉起成功不必
     * 重复 `startForegroundService`——自动切歌（[onTrackEnded] →
     * [playCurrent]）会频繁走到这里，息屏/后台态下重复调用会触发
     * Android 12+ 的后台启动限制而崩溃（ForegroundServiceStartNotAllowedException）。
     *
     * 兜底：万一在服务未起时处于后台（启动被系统拒绝），捕获异常放行——
     * 播放本体是进程内的 [exoPlayer]，不依赖此调用；回到前台后下一次
     * 切歌会重试拉起服务。
     */
    private fun ensureServiceStarted() {
        if (serviceStarted) return
        try {
            val intent = Intent(context, PlayerService::class.java)
            ContextCompat.startForegroundService(context, intent)
            serviceStarted = true
        } catch (error: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException 是 IllegalStateException
            // 子类：后台启动 FGS 被拒。服务通常已在运行（续播场景），忽略即可。
            Log.w(TAG, "startForegroundService denied (background?); playback continues", error)
        }
    }

    /** [PlayerService.onDestroy] 回调：服务已停止，下次播放需重新拉起。 */
    fun onServiceDestroyed() {
        serviceStarted = false
    }

    /**
     * 播放停止的统一收尾：先由调用方停掉 ExoPlayer，这里纠正播放态并发布。
     *
     * deckLoaded=false（解析空档期停下）时 publish 走「保留初值」分支——
     * 不在这里显式把 isPlaying 置 false，UI 会残留「正在播放」。
     */
    private fun haltStopped(error: String? = null) {
        _state.update {
            it.copy(isPlaying = false, isBuffering = false, error = error ?: it.error)
        }
        publish()
    }

    private fun publish() {
        // 原 bug：release 后回调仍可能触发 publish，访问已释放的 ExoPlayer 抛
        // IllegalStateException。置位后直接短路。
        if (released) return
        _state.update { current ->
            // 原 bug：切歌空档期（playCurrent 已换 currentTrack、旧 deck 仍在播）
            // publish 无条件读播放头/时长，会把旧曲的播放头与时长写进新曲状态
            // （500ms 轮询有 deckLoaded 守卫，publish 没有）。未装载时只刷新队列
            // 元信息，保留 playCurrent 写入的初值（positionMs=0 /
            // durationMs=track.durationMS / isPlaying=true）。
            if (!deckLoaded) {
                return@update current.copy(
                    queue = queue.queue,
                    currentIndex = queue.currentIndex,
                    currentTrack = queue.current(),
                    repeatMode = queue.repeatMode,
                    shuffleEnabled = queue.shuffleEnabled,
                    isFMMode = queue.isFMMode,
                    context = queue.context,
                    // 解析空档期旧 deck 仍在发声：保留 playCurrent 乐观写入的
                    // 「即将播放」，避免每次自动切歌 UI 闪一下暂停；仅当用户在
                    // 空档期明确暂停（userPauseIntent）时如实显示暂停——否则
                    // UI 播放态与实际脱节（原地暂停却显示播放中）。
                    isPlaying = if (userPauseIntent) false else current.isPlaying,
                    isBuffering = if (userPauseIntent) false else current.isBuffering,
                )
            }
            current.copy(
                queue = queue.queue,
                currentIndex = queue.currentIndex,
                currentTrack = queue.current(),
                isPlaying = exoPlayer.isPlaying,
                isBuffering = exoPlayer.playbackState == Player.STATE_BUFFERING,
                positionMs = exoPlayer.currentPosition.coerceAtLeast(0),
                durationMs = exoPlayer.duration.takeIf { it > 0 } ?: queue.current()?.durationMS ?: 0,
                repeatMode = queue.repeatMode,
                shuffleEnabled = queue.shuffleEnabled,
                isFMMode = queue.isFMMode,
                context = queue.context,
            )
        }
    }

    companion object {
        private const val TAG = "PlaybackEngine"

        /** 媒体下载用的桌面 Chrome UA（B 站 CDN 会拒绝 ExoPlayer/移动端 UA）。 */
        private const val PLAYER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private const val POSITION_POLL_MS = 500L
        private const val RESTART_THRESHOLD_MS = 4_000L
        private const val MAX_CONSECUTIVE_FAILURES = 5

        /** 曲目「稳定播放」阈值：实际播放超过此时长才视作成功并重置熔断计数。 */
        private const val STABLE_PLAY_MS = 5_000L

        @Volatile
        private var instance: PlaybackEngine? = null

        /**
         * 进程内单例入口。依赖须先经 [PlayerDependencies.provide] 注入
         * （AppGraph 启动时完成）。
         */
        fun acquire(context: Context): PlaybackEngine {
            instance?.let { return it }
            val dependencies = PlayerDependencies
            check(dependencies.isProvided) {
                "PlayerDependencies.provide(api, settings, scope) must run before playback"
            }
            return synchronized(this) {
                instance ?: PlaybackEngine(
                    context = context.applicationContext,
                    api = dependencies.requireApi(),
                    settings = dependencies.requireSettings(),
                    scope = dependencies.requireScope(),
                    localSourceProvider = dependencies.localSource,
                ).also { instance = it }
            }
        }

        fun peek(): PlaybackEngine? = instance
    }
}
