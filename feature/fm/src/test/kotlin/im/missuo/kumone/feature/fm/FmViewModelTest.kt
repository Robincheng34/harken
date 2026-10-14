// Ported from kumone by missuo (LGPL-3.0)
//
// 漫游 ViewModel 单测（T23 `FmViewModelTest.kt`，纯 JVM）。
//
// 通过 [FmPlaybackPort] 窄接口注入伪引擎，验证登录门控与播放态投影
// （含 iOS 语义「非 FM 模式不投影 currentTrack」）。

package im.missuo.kumone.feature.fm

import im.missuo.kumone.net.model.Track
import im.missuo.kumone.player.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FmViewModelTest {

    /** 伪引擎：记录四个命令调用。 */
    private class FakeEngine(initial: PlayerState) : FmPlaybackPort {
        override val state = MutableStateFlow(initial)
        var started = false
        var trashed = 0
        var nextCount = 0
        var toggles = 0

        override fun togglePlay() { toggles += 1 }
        override fun next() { nextCount += 1 }
        override fun startFM() { started = true }
        override fun fmNext() { nextCount += 1 }
        override fun fmTrash() { trashed += 1 }
    }

    @Test
    fun `logged out state shows login prompt`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val vm = FmViewModel(FakeEngine(PlayerState()), { false }, CoroutineScope(dispatcher))
            advanceUntilIdle()

            val state = vm.uiState.value
            assertTrue(state.showLoginPrompt)
            assertFalse(state.isFmMode)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `fm mode projects track from engine state`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val engine = FakeEngine(
                PlayerState(isFMMode = true, currentTrack = Track(id = 5, name = "fm song"), isPlaying = true),
            )
            val vm = FmViewModel(engine, { true }, CoroutineScope(dispatcher))
            advanceUntilIdle()

            val state = vm.uiState.value
            assertFalse(state.showLoginPrompt)
            assertTrue(state.isFmMode)
            assertTrue(state.isPlaying)
            assertEquals(5L, state.currentTrack?.id)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `non fm mode hides current track`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val engine = FakeEngine(
                PlayerState(isFMMode = false, currentTrack = Track(id = 9, name = "normal")),
            )
            val vm = FmViewModel(engine, { true }, CoroutineScope(dispatcher))
            advanceUntilIdle()

            // iOS: `player.isFMMode ? player.currentTrack : nil`
            val state = vm.uiState.value
            assertFalse(state.isFmMode)
            assertNull(state.currentTrack)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `commands forward to engine`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val engine = FakeEngine(PlayerState())
            val vm = FmViewModel(engine, { true }, CoroutineScope(dispatcher))
            advanceUntilIdle()

            vm.start()
            vm.togglePlay()
            vm.next()
            vm.trash()

            assertTrue(engine.started)
            assertEquals(1, engine.toggles)
            assertEquals(1, engine.nextCount)
            assertEquals(1, engine.trashed)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
