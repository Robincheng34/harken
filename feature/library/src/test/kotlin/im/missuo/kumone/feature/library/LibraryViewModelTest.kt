// Ported from kumone by missuo (LGPL-3.0)
//
// 我的页 ViewModel 单测（T24 `LibraryViewModelTest.kt`，纯 JVM）。
//
// 验证登录门控（未登录不取数）与歌单拆分（specialType != 5 → 创建；subscribed → 收藏）。

package im.missuo.kumone.feature.library

import im.missuo.kumone.net.api.NeteaseApi
import im.missuo.kumone.net.client.InMemoryCookieStore
import im.missuo.kumone.net.client.NeteaseClient
import im.missuo.kumone.net.model.PlaylistCreator
import im.missuo.kumone.net.model.PlaylistSummary
import im.missuo.kumone.net.model.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    private class FakeLibraryApi(
        private val profile: UserProfile? = UserProfile(userId = 1, nickname = "tester"),
        private val playlists: List<PlaylistSummary> = emptyList(),
    ) : NeteaseApi(NeteaseClient(InMemoryCookieStore())) {
        override suspend fun userAccount(): UserProfile? = profile

        override suspend fun userPlaylists(uid: Long, limit: Int, offset: Int): List<PlaylistSummary> =
            playlists
    }

    @Test
    fun `logged out state exposes no personal rows`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val vm = LibraryViewModel(
                api = FakeLibraryApi(),
                isLoggedIn = { false },
                scope = CoroutineScope(dispatcher),
            )
            vm.load()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertFalse(state.isLoggedIn)
            assertFalse(state.showPersonalRows)
            assertTrue(state.createdPlaylists.isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `logged in state splits created and subscribed playlists by ownership`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val me = PlaylistCreator(userId = 1, nickname = "tester")
            val other = PlaylistCreator(userId = 999, nickname = "someone")
            val api = FakeLibraryApi(
                playlists = listOf(
                    // specialType 5 = 我喜欢的音乐（本人创建，但被排除）
                    PlaylistSummary(id = 1, name = "我喜欢的音乐", specialType = 5, creator = me),
                    // 本人创建 → created
                    PlaylistSummary(id = 2, name = "我建的", creator = me),
                    // 他人创建 → subscribed
                    PlaylistSummary(id = 3, name = "收藏的", creator = other),
                ),
            )
            val vm = LibraryViewModel(
                api = api,
                isLoggedIn = { true },
                scope = CoroutineScope(dispatcher),
            )
            vm.load()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertTrue(state.isLoggedIn)
            assertTrue(state.showPersonalRows)
            assertEquals("tester", state.nickname)
            // 创建：creator == 本人 且 非 specialType 5
            assertEquals(listOf(2L), state.createdPlaylists.map { it.id })
            // 收藏：creator != 本人
            assertEquals(listOf(3L), state.subscribedPlaylists.map { it.id })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `surfaces error when account fetch fails`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val vm = LibraryViewModel(
                api = FakeLibraryApi(profile = null),
                isLoggedIn = { true },
                scope = CoroutineScope(dispatcher),
            )
            vm.load()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertTrue(state.error != null)
            assertFalse(state.isLoading)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
