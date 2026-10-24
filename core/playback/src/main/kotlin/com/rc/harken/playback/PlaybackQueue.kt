// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken.playback

import com.rc.harken.net.model.Track
import kotlin.random.Random

/**
 * The playback queue state machine (iOS `PlayerService` queue semantics, minus
 * the engine): repeat modes, shuffle order, personal-FM behaviour, play-next
 * insertions and queue editing.
 *
 * Semantics ported from the iOS player:
 * - `advance()` consumes play-next insertions first, then walks the queue;
 *   `RepeatMode.ALL` wraps at the end, OFF/ONE stop (repeat-one replay is the
 *   engine's job). In FM mode it returns null — the upper layer fetches tracks.
 * - `previous()` returns null in FM mode (上一首禁用) and at the head of the
 *   queue unless `RepeatMode.ALL`; the engine restarts the current track on
 *   null.
 * - Shuffle keeps the current track at index 0 with the rest randomised
 *   (iOS `reshuffle(keeping:)`, filtered by track id); the listed order is
 *   preserved and restored when shuffle is turned off (iOS keeps the two
 *   orders in separate arrays — [queue] is the active one here).
 * - `load()` resets FM mode, insertions and shuffle order like iOS `play(...)`;
 *   an empty list clears the queue entirely.
 *
 * Not thread-safe: the player engine owns the single instance on the main
 * thread.
 */
class PlaybackQueue {

    /** The active queue; when [shuffleEnabled] is on it is the shuffled order. */
    var queue: List<Track> = emptyList()
        private set

    /** The order the queue was loaded in; restored when shuffle is turned off. */
    private var listedOrder: List<Track> = emptyList()

    /** Index of the current track within [queue], or -1. */
    var currentIndex: Int = -1
        private set

    /** List repeat mode; cycle with `repeatMode = repeatMode.next`. */
    var repeatMode: RepeatMode = RepeatMode.OFF

    /**
     * Random order. Enabling reshuffles the queue keeping the current track
     * first (iOS `setQueueOrder(.shuffled)`); disabling restores the listed
     * order. Ignored while in FM mode (iOS parity).
     */
    var shuffleEnabled: Boolean = false
        set(value) {
            if (isFMMode) return
            if (field == value) return
            field = value
            if (value) reshuffleKeepingCurrent() else restoreListedOrder()
        }

    /** Personal-FM mode: 上一首禁用, advance delegates to the upper layer. */
    var isFMMode: Boolean = false

    /** Where the current queue came from. */
    var context: PlayContext? = null

    /** The kind of list the queue was loaded from. */
    var source: PlaySource = PlaySource.NONE

    /** Tracks inserted via [insertNext], consumed before the queue advances. */
    private val playNext: ArrayDeque<Track> = ArrayDeque()

    /** The sounding track while it is an inserted (play-next) track. */
    private var currentOverride: Track? = null

    /** Number of tracks in the active queue. */
    val size: Int get() = queue.size

    /**
     * Loads a new queue, like iOS `play(tracks:source:startAt:context:)`:
     * resets FM mode and play-next insertions, applies the shuffle order when
     * enabled, and clamps [startIndex]. An empty list clears the queue
     * entirely — a documented deviation from the iOS no-op guard, so the
     * Android player can stop playback by loading an empty list.
     */
    fun load(
        tracks: List<Track>,
        startIndex: Int,
        context: PlayContext?,
        source: PlaySource,
    ) {
        this.source = source
        source.sourceID = context?.id ?: 0L
        if (tracks.isEmpty()) {
            this.context = null
            this.source = PlaySource.NONE
            this.source.sourceID = 0L
            isFMMode = false
            playNext.clear()
            currentOverride = null
            listedOrder = emptyList()
            queue = emptyList()
            currentIndex = -1
            return
        }
        this.context = context
        isFMMode = false
        playNext.clear()
        currentOverride = null
        listedOrder = tracks.toList()

        if (shuffleEnabled) {
            val start = tracks.getOrNull(startIndex) ?: tracks.firstOrNull()
            queue = if (start == null) emptyList() else shuffleWithCurrentFirst(tracks, start)
            currentIndex = if (queue.isEmpty()) -1 else 0
        } else {
            queue = listedOrder
            currentIndex = if (tracks.isEmpty()) -1 else startIndex.coerceIn(0, tracks.size - 1)
        }
    }

    /** The sounding track, or null when the queue is empty. */
    fun current(): Track? = currentOverride ?: queue.getOrNull(currentIndex)

    /**
     * Moves to the next track and returns it:
     * play-next insertions first, then the queue; `RepeatMode.ALL` wraps,
     * OFF/ONE return null at the end; FM mode always returns null.
     */
    fun advance(): Track? {
        if (isFMMode) return null
        val inserted = playNext.removeFirstOrNull()
        if (inserted != null) {
            currentOverride = inserted
            return inserted
        }
        if (queue.isEmpty() || currentIndex < 0) return null
        var index = currentIndex + 1
        if (index >= queue.size) {
            if (repeatMode != RepeatMode.ALL) return null
            index = 0
        }
        currentIndex = index
        currentOverride = null
        return queue[index]
    }

    /**
     * Moves to the previous track and returns it; FM mode returns null
     * (上一首禁用), as does the head of the queue unless `RepeatMode.ALL`
     * (the engine then restarts the current track).
     */
    fun previous(): Track? {
        if (isFMMode) return null
        if (queue.isEmpty() || currentIndex < 0) return null
        var index = currentIndex - 1
        if (index < 0) {
            if (repeatMode != RepeatMode.ALL) return null
            index = queue.size - 1
        }
        currentIndex = index
        currentOverride = null
        return queue[index]
    }

    /** Jumps to a queue position; out-of-range indices are ignored. */
    fun jumpTo(index: Int) {
        if (index < 0 || index >= queue.size) return
        currentIndex = index
        currentOverride = null
    }

    /** Inserts a track right after the current one ("播放下一首"). */
    fun insertNext(track: Track) {
        playNext.addLast(track)
    }

    /**
     * Removes a queue entry from both the active and the listed order. In list
     * order the current track cannot be removed (iOS parity); in shuffle order
     * it can — the following track takes over.
     */
    fun removeAt(index: Int) {
        if (index < 0 || index >= queue.size) return
        if (index == currentIndex && !shuffleEnabled) return
        val removed = queue[index]
        val remainingActive = ArrayList(queue)
        remainingActive.removeAt(index)
        queue = remainingActive
        val remainingListed = ArrayList(listedOrder)
        val listedIndex = remainingListed.indexOfFirst { it === removed }
        if (listedIndex >= 0) {
            remainingListed.removeAt(listedIndex)
            listedOrder = remainingListed
        }
        when {
            index < currentIndex -> currentIndex -= 1
            index == currentIndex -> currentOverride = null
        }
    }

    /**
     * Re-inserts a previously removed track at [index] (队列面板「撤销删除」）。
     *
     * 只恢复队列数据，不改变当前播放曲：若插入点在当前曲之前，当前索引顺延一位。
     */
    fun insertAt(index: Int, track: Track) {
        if (index < 0 || index > queue.size) return
        val active = ArrayList(queue)
        active.add(index, track)
        queue = active
        val listed = ArrayList(listedOrder)
        listed.add(index.coerceIn(0, listed.size), track)
        listedOrder = listed
        if (index <= currentIndex) currentIndex += 1
    }

    /**
     * Moves the track at [from] to [to] (队列面板长按拖拽排序).
     *
     * Both the active order and [listedOrder] move the same entry (官方语义：拖动
     * 提交的是整表重排，两份顺序保持一致，随机切回原始顺序时不串位). [currentIndex]
     * follows the current track when it is the one being moved; tracks crossing
     * over the current position compensate the index by one.
     */
    fun move(from: Int, to: Int) {
        if (from == to) return
        if (from !in queue.indices || to !in queue.indices) return
        val moved = queue[from]
        val active = ArrayList(queue)
        active.removeAt(from)
        active.add(to, moved)
        queue = active
        // listedOrder 同步移动同一条目（按实例定位，语义同 removeAt）。
        val listedIndex = listedOrder.indexOfFirst { it === moved }
        if (listedIndex >= 0) {
            val listed = ArrayList(listedOrder)
            listed.removeAt(listedIndex)
            val listedTarget = (listedIndex + (to - from)).coerceIn(0, listed.size)
            listed.add(listedTarget, moved)
            listedOrder = listed
        }
        currentIndex = when {
            currentIndex == from -> to
            from < currentIndex && to >= currentIndex -> currentIndex - 1
            from > currentIndex && to <= currentIndex -> currentIndex + 1
            else -> currentIndex
        }
    }

    /** The next [limit] tracks: play-next insertions, then the queue rest. */
    fun upcoming(limit: Int): List<Track> {
        if (limit <= 0) return emptyList()
        val rest = if (queue.isNotEmpty() && currentIndex >= 0) {
            queue.drop(currentIndex + 1)
        } else {
            emptyList()
        }
        return (playNext + rest).take(limit)
    }

    private fun reshuffleKeepingCurrent() {
        if (queue.isEmpty()) return
        val currentTrack = current() ?: return
        val rest = queue.filter { it.id != currentTrack.id }
        queue = listOf(currentTrack) + rest.shuffled(Random.Default)
        currentIndex = 0
        currentOverride = null
    }

    private fun restoreListedOrder() {
        // 插播曲正在发声时（currentOverride != null），「基准曲目」是被它顶掉的
        // queue[currentIndex]——恢复顺序要以它定位，而不是 current()（那是插播曲，
        // 不在 listedOrder 里）。
        val base = currentOverride ?: current()
        queue = listedOrder
        if (base == null) {
            currentIndex = -1
            currentOverride = null
            return
        }
        val index = listedOrder.indexOfFirst { it.id == base.id }
        if (index >= 0) {
            if (currentOverride == null) {
                // 当前曲就是基准曲：索引指向它、清除 override。
                currentIndex = index
                currentOverride = null
            } else {
                // 插播曲仍在发声：保留 override，currentIndex 指回它顶掉的位置——
                // 插播曲播完后 advance() 从基准曲的下一首继续（repeat-all 照常循环）。
                currentIndex = index
            }
        } else {
            // 基准曲也不在 listedOrder（队列被外部改过）：保底从队首继续，
            // 插播曲若仍在发声则播完后 advance 从队首取曲。
            currentIndex = -1
        }
    }

    private fun shuffleWithCurrentFirst(tracks: List<Track>, start: Track): List<Track> {
        val rest = tracks.filter { it.id != start.id }
        return listOf(start) + rest.shuffled(Random.Default)
    }
}
