// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken.player

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

/**
 * 把「自研逻辑播放队列」暴露给 MediaSession 的 [ForwardingPlayer] 包装层。
 *
 * 背景：[PlaybackEngine] 为保证切歌不断播与音质回退，每次只在 ExoPlayer 里装填
 * 单个 MediaItem（playCurrent 里的 setMediaItem），完整队列由 :core:playback 的
 * PlaybackQueue 管理。于是底层 ExoPlayer 的 timeline 永远只有 1 首，
 * `COMMAND_SEEK_TO_NEXT` 不可用；`DefaultMediaNotificationProvider` 按
 * `Player.getAvailableCommands()` 决定按钮，只生成「上一首 / 播放暂停」两个
 * action，下拉通知栏的媒体控制条就缺了「下一首」。
 *
 * 本类只做两件事，不触碰播放本体：
 * 1. 在 delegate 的 [Player.Commands] 上补声明「可切下一首 / 可切上一首」；
 * 2. 把这两个命令转发回 [PlaybackEngine.next] / [PlaybackEngine.previous]，
 *    由引擎现有的队列状态机（含 FM、连败熔断、seek(0) 兜底）执行。
 *
 * 切歌命令**不能**调用 super：super 会去操作只有 1 首的 ExoPlayer timeline，
 * 既切不动也可能打乱 `deckLoaded` / 解析代际。其余所有方法（播放状态、
 * metadata、timeline、listener 转发等）沿用 [ForwardingPlayer] 的默认委托语义，
 * 因此 `playWhenReady`、`mediaItemCount` 等读到的仍是真实 ExoPlayer。
 */
internal class QueueAwarePlayer(
    delegate: Player,
    private val engine: PlaybackEngine,
) : ForwardingPlayer(delegate) {

    /**
     * 在 delegate 命令集基础上追加两个切歌命令。
     *
     * media3 1.5.1 的 `Player.Commands.Builder` 提供 public 的
     * `addAll(Commands)` / `add(int)` / `build()`；Commands 不可变，故每次构建新实例。
     * 只加 `COMMAND_SEEK_TO_NEXT` / `COMMAND_SEEK_TO_PREVIOUS`，不加
     * `*_MEDIA_ITEM` 版本：后者会引导外部 controller 直接去动 ExoPlayer 的单曲
     * timeline（见 MediaSessionLegacyStub / MediaSessionStub 的分派逻辑）。
     */
    override fun getAvailableCommands(): Player.Commands =
        Player.Commands.Builder()
            .addAll(super.getAvailableCommands())
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .build()

    /**
     * 与 [getAvailableCommands] 保持一致。
     *
     * [ForwardingPlayer.isCommandAvailable] 默认把查询转发给 delegate，而单曲
     * timeline 的 ExoPlayer 对这两个命令返回 false；声明了命令却查询不到会让
     * `MediaSessionLegacyStub.onSkipToNext/onSkipToPrevious` 误走 `*_MEDIA_ITEM`
     * 分支，把切歌打回只有 1 首的 ExoPlayer timeline（表现为按了没反应）。
     * 这也是 ForwardingPlayer 自身 Javadoc 对「增删命令」的一致性要求。
     */
    override fun isCommandAvailable(command: Int): Boolean =
        command == Player.COMMAND_SEEK_TO_NEXT ||
            command == Player.COMMAND_SEEK_TO_PREVIOUS ||
            super.isCommandAvailable(command)

    /** 通知栏 / 蓝牙 / 媒体按键的「下一首」统一走引擎逻辑队列。 */
    override fun seekToNext() {
        engine.next()
    }

    /** 通知栏 / 蓝牙 / 媒体按键的「上一首」统一走引擎逻辑队列。 */
    override fun seekToPrevious() {
        engine.previous()
    }

    /** 逻辑队列总可向后切；到底时 [PlaybackEngine.next] 自带 haltStopped 兜底。 */
    override fun hasNextMediaItem(): Boolean = true

    /** 逻辑队列总可向前切；到头时 [PlaybackEngine.previous] 自带 seek(0) 兜底。 */
    override fun hasPreviousMediaItem(): Boolean = true
}
