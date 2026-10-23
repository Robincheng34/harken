// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 播放页 UI 状态机的 JVM 单测（P1-3 补位：本模块此前零测试类）。
//
// `NowPlayingViewModel` 是纯 Kotlin（面板开合 + 歌词拖拽态），不碰 Android
// 与 Compose，因此可在 JVM 上直接跑。核心契约是「歌词面板与队列面板互斥」——
// 两个面板都铺满播放页下半屏，同时展开会互相遮挡。

package com.rc.harken.feature.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingViewModelTest {

    @Test
    fun `panels start closed`() = runTest {
        val viewModel = NowPlayingViewModel()

        val state = viewModel.uiState.first()
        assertFalse(state.showLyricsPanel)
        assertFalse(state.showQueuePanel)
        assertFalse(state.isLyricsScrubbing)
    }

    @Test
    fun `opening lyrics panel closes the queue panel`() = runTest {
        val viewModel = NowPlayingViewModel()

        viewModel.toggleQueuePanel()
        assertTrue(viewModel.uiState.first().showQueuePanel)

        viewModel.toggleLyricsPanel()

        val state = viewModel.uiState.first()
        assertTrue(state.showLyricsPanel)
        assertFalse(state.showQueuePanel)
    }

    @Test
    fun `opening queue panel closes the lyrics panel`() = runTest {
        val viewModel = NowPlayingViewModel()

        viewModel.toggleLyricsPanel()
        assertTrue(viewModel.uiState.first().showLyricsPanel)

        viewModel.toggleQueuePanel()

        val state = viewModel.uiState.first()
        assertTrue(state.showQueuePanel)
        assertFalse(state.showLyricsPanel)
    }

    @Test
    fun `toggling the same panel twice returns to closed`() = runTest {
        val viewModel = NowPlayingViewModel()

        viewModel.toggleLyricsPanel()
        viewModel.toggleLyricsPanel()

        assertFalse(viewModel.uiState.first().showLyricsPanel)
    }

    @Test
    fun `lyrics scrubbing flag follows touch lifecycle`() = runTest {
        val viewModel = NowPlayingViewModel()

        viewModel.setLyricsScrubbing(true)
        assertTrue(viewModel.uiState.first().isLyricsScrubbing)

        viewModel.setLyricsScrubbing(false)
        assertFalse(viewModel.uiState.first().isLyricsScrubbing)
    }

    // ---- 沉浸式歌词（isLyricsImmersive）状态机 ----
    // 契约：沉浸只在「歌词面板已开」时才有意义；任何其他面板动作都要清掉它，
    // 否则会残留出「chrome 已隐藏但计时器不再跑」的卡死态。

    @Test
    fun `panels start non-immersive`() = runTest {
        val viewModel = NowPlayingViewModel()

        assertFalse(viewModel.uiState.first().isLyricsImmersive)
    }

    @Test
    fun `immersive mode can be entered and exited`() = runTest {
        val viewModel = NowPlayingViewModel()
        viewModel.toggleLyricsPanel()

        viewModel.enterLyricsImmersive()
        assertTrue(viewModel.uiState.first().isLyricsImmersive)

        viewModel.exitLyricsImmersive()
        assertFalse(viewModel.uiState.first().isLyricsImmersive)
    }

    @Test
    fun `entering immersive twice is idempotent`() = runTest {
        val viewModel = NowPlayingViewModel()
        viewModel.toggleLyricsPanel()

        viewModel.enterLyricsImmersive()
        viewModel.enterLyricsImmersive()

        assertTrue(viewModel.uiState.first().isLyricsImmersive)
    }

    @Test
    fun `exiting immersive twice is idempotent`() = runTest {
        val viewModel = NowPlayingViewModel()

        // 未进入时退出应保持 false，不得意外置真。
        viewModel.exitLyricsImmersive()
        assertFalse(viewModel.uiState.first().isLyricsImmersive)
    }

    @Test
    fun `toggling lyrics panel leaves immersive mode`() = runTest {
        val viewModel = NowPlayingViewModel()
        viewModel.toggleLyricsPanel()
        viewModel.enterLyricsImmersive()

        // 沉浸中切回封面：必须同时退出沉浸，否则封面页会保持 chrome 隐藏。
        viewModel.toggleLyricsPanel()

        val state = viewModel.uiState.first()
        assertFalse(state.showLyricsPanel)
        assertFalse(state.isLyricsImmersive)
    }

    @Test
    fun `opening queue panel leaves immersive mode`() = runTest {
        val viewModel = NowPlayingViewModel()
        viewModel.toggleLyricsPanel()
        viewModel.enterLyricsImmersive()

        // 回归用例：曾经这里不重置 isLyricsImmersive，导致关闭队列后播放页
        // 卡在沉浸态（chrome 隐藏）且「5 秒无操作自动沉浸」的计时不再生效。
        viewModel.toggleQueuePanel()
        assertFalse(viewModel.uiState.first().isLyricsImmersive)

        viewModel.toggleQueuePanel()
        val state = viewModel.uiState.first()
        assertFalse(state.showQueuePanel)
        assertFalse(state.isLyricsImmersive)
    }

    @Test
    fun `closing queue panel does not resurrect immersive mode`() = runTest {
        val viewModel = NowPlayingViewModel()
        viewModel.toggleLyricsPanel()
        viewModel.enterLyricsImmersive()
        viewModel.toggleQueuePanel() // 开队列（退出沉浸）
        viewModel.toggleQueuePanel() // 关队列

        val state = viewModel.uiState.first()
        assertFalse(state.showQueuePanel)
        assertFalse(state.showLyricsPanel)
        assertFalse(state.isLyricsImmersive)
    }

    @Test
    fun `markInteraction advances the idle timestamp`() = runTest {
        val viewModel = NowPlayingViewModel()
        val before = viewModel.lastInteractionMs

        // 即使同一毫秒内连点，时间戳也不应倒退。
        viewModel.markInteraction()
        viewModel.markInteraction()

        assertTrue(viewModel.lastInteractionMs >= before)
    }
}
