// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken.player

import com.rc.harken.net.lyrics.ParsedLyrics
import com.rc.harken.net.model.Track
import com.rc.harken.playback.PlayContext
import com.rc.harken.playback.PlaySource
import com.rc.harken.playback.RepeatMode

/**
 * 全部播放 UI 消费的状态快照（架构文档 §4.1）。
 *
 * 由 [PlaybackEngine] 发布、MusicController 暴露；迷你条 / 播放页 / 漫游页
 * 均以此单一数据源渲染。
 */
data class PlayerState(
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val shuffleEnabled: Boolean = false,
    val isFMMode: Boolean = false,
    val context: PlayContext? = null,
    /** 实际服务音质（含降级后的档位）。 */
    val servedQuality: String? = null,
    val isTrial: Boolean = false,
    /**
     * 当前曲实际使用的第三方音源展示名（iOS `PlayerService.swift:143`
     * `@Published unblockSource`）。**非空即代表走了灰色解锁**；null = 官方源。
     * 空串见 [UNBLOCK_SOURCE_UNNAMED]（命中解锁但源名未知）。
     */
    val unblockSource: String? = null,
    val lyrics: ParsedLyrics? = null,
    val error: String? = null,
)
