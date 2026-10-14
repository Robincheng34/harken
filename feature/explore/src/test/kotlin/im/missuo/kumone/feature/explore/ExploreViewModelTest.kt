// Ported from kumone by missuo (LGPL-3.0)
//
// 发现页 ViewModel 单测（T19 `ExploreViewModelTest.kt`，纯 JVM）。
//
// 覆写 NeteaseApi 目录接口注入确定性数据，验证：select 切换到推荐/排行榜/精品分类分别
// 拉取对应数据；精品歌单按 `before` 游标分页；切换分类会重置列表并重新加载。

package im.missuo.kumone.feature.explore

import im.missuo.kumone.net.api.NeteaseApi
import im.missuo.kumone.net.client.InMemoryCookieStore
import im.missuo.kumone.net.client.NeteaseClient
import im.missuo.kumone.net.model.HighQualityResponse
import im.missuo.kumone.net.model.PlaylistSummary
import im.missuo.kumone.net.model.ToplistItem
import im.missuo.kumone.net.model.TopPlaylistResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExploreViewModelTest {

    private class FakeExploreApi(
        private val playlistsByCat: Map<String, List<PlaylistSummary>> = emptyMap(),
        private val toplists: List<ToplistItem> = emptyList(),
        private val highQuality: (Long) -> HighQualityResponse = { HighQualityResponse() },
    ) : NeteaseApi(NeteaseClient(InMemoryCookieStore())) {
        override suspend fun personalizedPlaylists(limit: Int): List<PlaylistSummary> =
            playlistsByCat[CATEGORY_RECOMMENDED] ?: emptyList()

        override suspend fun highQualityPlaylists(
            category: String,
            limit: Int,
            before: Long,
        ): HighQualityResponse = highQuality(before)

        override suspend fun toplists(): List<ToplistItem> = toplists

        override suspend fun topPlaylists(
            category: String,
            order: String,
            limit: Int,
            offset: Int,
        ): TopPlaylistResponse = TopPlaylistResponse(
            playlists = playlistsByCat[category] ?: emptyList(),
            more = false,
        )
    }

    private fun viewModel(api: NeteaseApi, dispatcher: TestDispatcher): ExploreViewModel =
        ExploreViewModel(api, CoroutineScope(dispatcher))

    @Test
    fun `select recommended loads recommended playlists without pagination`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val api = FakeExploreApi(
                playlistsByCat = mapOf(
                    CATEGORY_RECOMMENDED to listOf(
                        PlaylistSummary(id = 1L, name = "r1"),
                        PlaylistSummary(id = 2L, name = "r2"),
                    ),
                ),
            )
            val vm = viewModel(api, dispatcher)
            vm.select(CATEGORY_RECOMMENDED)
            advanceUntilIdle()

            val state = vm.uiState.value
            assertFalse(state.isLoading)
            assertEquals(CATEGORY_RECOMMENDED, state.selectedCategory)
            assertEquals(2, state.playlists.size)
            assertFalse(state.hasMore)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `select toplists loads toplists without pagination`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val api = FakeExploreApi(
                toplists = listOf(
                    ToplistItem(id = 19_723_756L, name = "飙升榜"),
                    ToplistItem(id = 999L, name = "非官方"),
                ),
            )
            val vm = viewModel(api, dispatcher)
            vm.select(CATEGORY_TOPLISTS)
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(CATEGORY_TOPLISTS, state.selectedCategory)
            assertEquals(2, state.toplists.size)
            assertFalse(state.hasMore)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `select high quality paginates via before cursor`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val api = FakeExploreApi(
                highQuality = { before ->
                    when (before) {
                        0L -> HighQualityResponse(
                            playlists = listOf(
                                PlaylistSummary(id = 1L, name = "hq1"),
                                PlaylistSummary(id = 2L, name = "hq2"),
                            ),
                            lasttime = 100L,
                            more = true,
                        )
                        100L -> HighQualityResponse(
                            playlists = listOf(PlaylistSummary(id = 3L, name = "hq3")),
                            lasttime = 200L,
                            more = false,
                        )
                        else -> HighQualityResponse()
                    }
                },
            )
            val vm = viewModel(api, dispatcher)
            vm.select(CATEGORY_HIGH_QUALITY)
            advanceUntilIdle()

            var state = vm.uiState.value
            assertEquals(CATEGORY_HIGH_QUALITY, state.selectedCategory)
            assertEquals(2, state.playlists.size)
            assertTrue(state.hasMore)

            vm.loadMore()
            advanceUntilIdle()

            state = vm.uiState.value
            assertEquals(3, state.playlists.size)
            assertFalse(state.hasMore)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `selecting a different category resets and reloads`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val api = FakeExploreApi(
                playlistsByCat = mapOf(
                    CATEGORY_RECOMMENDED to listOf(PlaylistSummary(id = 1L, name = "r1")),
                    "华语" to listOf(PlaylistSummary(id = 10L, name = "c1")),
                ),
            )
            val vm = viewModel(api, dispatcher)
            vm.select(CATEGORY_RECOMMENDED)
            advanceUntilIdle()
            assertEquals(1, vm.uiState.value.playlists.size)
            assertEquals("r1", vm.uiState.value.playlists.first().name)

            vm.select("华语")
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals("华语", state.selectedCategory)
            assertEquals(1, state.playlists.size)
            assertEquals("c1", state.playlists.first().name)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
