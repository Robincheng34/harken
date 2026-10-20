// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import im.missuo.kumone.net.api.NeteaseApi
import im.missuo.kumone.net.lyrics.LyricsParser
import im.missuo.kumone.net.model.Track
import im.missuo.kumone.playback.AudioQuality
import im.missuo.kumone.playback.PlayContext
import im.missuo.kumone.playback.PlaySource
import im.missuo.kumone.playback.PlaybackQueue
import im.missuo.kumone.playback.RepeatMode
import im.missuo.kumone.storage.download.LocalSongSource
import im.missuo.kumone.storage.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
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
 * 职责：驱动 [im.missuo.kumone.playback.PlaybackQueue] 状态机、解析播放地址
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
    internal val exoPlayer: ExoPlayer = ExoPlayer.Builder(context).build()

    private val resolver = SongUrlResolver(api, settings, localSourceProvider = localSourceProvider)
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
        queue.load(tracks, startIndex, context, source)
        consecutiveFailures = 0
        publish()
        playCurrent()
    }

    fun togglePlay() {
        val current = queue.current() ?: return
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
        } else {
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

    fun next() {
        if (queue.isFMMode) {
            fmNext()
            return
        }
        val next = queue.advance()
        if (next == null) {
            exoPlayer.pause()
            exoPlayer.seekTo(0)
            publish()
            return
        }
        playCurrent()
    }

    /**
     * 上一首；播放超过 4 秒时回到本曲开头（iOS `previous()` 语义）。
     * FM 模式下 [PlaybackQueue.previous] 返回 null，同样重启当前曲。
     */
    fun previous() {
        if (exoPlayer.currentPosition > RESTART_THRESHOLD_MS) {
            exoPlayer.seekTo(0)
            return
        }
        val previous = queue.previous()
        if (previous == null) {
            exoPlayer.seekTo(0)
            return
        }
        playCurrent()
    }

    fun seekTo(positionMs: Long) {
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
                _state.update { it.copy(error = "漫游已结束") }
                publish()
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
            consecutiveFailures = 0
            exoPlayer.setMediaItem(buildMediaItem(track, resolved))
            exoPlayer.prepare()
            exoPlayer.play()
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

    private fun buildMediaItem(track: Track, resolved: ResolvedSongUrl): MediaItem =
        MediaItem.Builder()
            .setUri(resolved.url)
            .setMediaId(track.id.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.name)
                    .setArtist(track.artistNames)
                    .setAlbumTitle(track.album.name)
                    .setArtworkUri(track.album.picUrl?.let(Uri::parse))
                    .build(),
            )
            .build()

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
            publish()
            return
        }
        consecutiveFailures += 1
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            // 连续失败达到上限：停下并保留 state.error（onPlayerError 已写入）。
            exoPlayer.pause()
            publish()
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
            publish()
            return
        }
        playCurrent()
    }

    /** 解析失败（无地址/全部回退失败）：计数、报错、按 iOS 策略续跳或停止。 */
    private suspend fun handleResolveFailure(track: Track) {
        consecutiveFailures += 1
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
        private const val POSITION_POLL_MS = 500L
        private const val RESTART_THRESHOLD_MS = 4_000L
        private const val MAX_CONSECUTIVE_FAILURES = 5

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
