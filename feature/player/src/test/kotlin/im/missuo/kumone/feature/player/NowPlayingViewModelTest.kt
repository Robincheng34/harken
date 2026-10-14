// Ported from kumone by missuo (LGPL-3.0)
//
// 播放页 UI 状态机的 JVM 单测（P1-3 补位：本模块此前零测试类）。
//
// `NowPlayingViewModel` 是纯 Kotlin（面板开合 + 歌词拖拽态），不碰 Android
// 与 Compose，因此可在 JVM 上直接跑。核心契约是「歌词面板与队列面板互斥」——
// 两个面板都铺满播放页下半屏，同时展开会互相遮挡。

package im.missuo.kumone.feature.player

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
}
