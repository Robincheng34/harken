// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// `MusicController` → [FmPlaybackPort] 适配器。
//
// `MusicController` 位于 `:core:player`（依赖 Android Context，无法在纯 JVM 单测中
// 构造），而 [FmPlaybackPort] 声明在 `:feature:fm`。为保持依赖方向（feature → core，
// 绝不反向）且让 FmViewModel 可单测，此处用适配器把具体控制器收敛到窄接口。

package com.rc.harken.feature.fm

import com.rc.harken.player.MusicController
import com.rc.harken.player.PlayerState
import kotlinx.coroutines.flow.StateFlow

/** 将 [MusicController] 适配为 [FmPlaybackPort]。 */
class MusicControllerFmAdapter(
    private val controller: MusicController,
) : FmPlaybackPort {
    override val state: StateFlow<PlayerState> get() = controller.state

    override fun togglePlay() = controller.togglePlay()

    override fun next() = controller.next()

    override fun startFM() = controller.startFM()

    override fun fmNext() = controller.fmNext()

    override fun fmTrash() = controller.fmTrash()
}
