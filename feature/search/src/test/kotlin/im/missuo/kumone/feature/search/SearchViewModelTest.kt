// Ported from kumone by missuo (LGPL-3.0)
//
// 搜索页 ViewModel 单测（P1-1 收尾，T20 `SearchViewModelTest.kt`，纯 JVM）。
//
// 覆写 NeteaseApi 的 search / searchSuggest 注入确定性数据，验证 iOS
// `SearchViewModel` 语义：
//  - 空查询不触发检索；
//  - `ALL` Tab 并发合并四个维度；
//  - 单类型 Tab 只拉该类型；
//  - `loadedTabs` 惰性加载：已加载的 Tab 不重复请求（iOS `guard !loadedTabs.contains`）；
//  - 查询词变化清空结果与已加载集合（iOS `setQuery`）；
//  - 输入触发联想建议。

package im.missuo.kumone.feature.search

import im.missuo.kumone.net.api.NeteaseApi
import im.missuo.kumone.net.client.InMemoryCookieStore
import im.missuo.kumone.net.client.NeteaseClient
import im.missuo.kumone.net.model.AlbumSummary
import im.missuo.kumone.net.model.ArtistSummary
import im.missuo.kumone.net.model.PlaylistSummary
import im.missuo.kumone.net.model.SearchResult
import im.missuo.kumone.net.model.SearchSuggestBody
import im.missuo.kumone.net.model.SearchType
import im.missuo.kumone.net.model.Track
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    /** 记录每次 search 的 (type, limit)，便于断言惰性加载与分页条数。 */
    private class FakeSearchApi : NeteaseApi(NeteaseClient(InMemoryCookieStore())) {
        val calls = mutableListOf<Pair<Int, Int>>()

        override suspend fun search(
            keywords: String,
            type: Int,
            limit: Int,
            offset: Int,
        ): SearchResult {
            calls += type to limit
            return when (type) {
                SearchType.SONGS.value -> SearchResult(songs = listOf(Track(id = 1L, name = "song")))
                SearchType.ARTISTS.value -> SearchResult(artists = listOf(ArtistSummary(id = 2L, name = "artist")))
                SearchType.ALBUMS.value -> SearchResult(albums = listOf(AlbumSummary(id = 3L, name = "album")))
                SearchType.PLAYLISTS.value -> SearchResult(playlists = listOf(PlaylistSummary(id = 4L, name = "pl")))
                else -> SearchResult()
            }
        }

        override suspend fun searchSuggest(keywords: String): SearchSuggestBody? =
            SearchSuggestBody(songs = listOf(Track(id = 9L, name = "suggest")))
    }

    private fun viewModel(api: NeteaseApi, dispatcher: TestDispatcher): SearchViewModel =
        SearchViewModel(api, CoroutineScope(dispatcher))

    @Test
    fun `all tab merges results from four types`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val vm = viewModel(FakeSearchApi(), dispatcher)
            vm.setQuery("test")
            vm.search()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(1, state.songs.size)
            assertEquals(1, state.artists.size)
            assertEquals(1, state.albums.size)
            assertEquals(1, state.playlists.size)
            assertTrue(state.error == null)
            assertTrue(SearchTab.ALL in state.loadedTabs)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `all tab uses iOS preview limits`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val api = FakeSearchApi()
            val vm = viewModel(api, dispatcher)
            vm.setQuery("test")
            vm.search()
            advanceUntilIdle()

            // iOS: ALL → songs 12 / artists 10 / albums 10 / playlists 10
            val limits = api.calls.toMap()
            assertEquals(12, limits[SearchType.SONGS.value])
            assertEquals(10, limits[SearchType.ARTISTS.value])
            assertEquals(10, limits[SearchType.ALBUMS.value])
            assertEquals(10, limits[SearchType.PLAYLISTS.value])
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `single tab only fetches its own type with iOS limit`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val api = FakeSearchApi()
            val vm = viewModel(api, dispatcher)
            vm.setQuery("test")
            vm.selectTab(SearchTab.SONGS)
            advanceUntilIdle()

            assertEquals(listOf(SearchType.SONGS.value to 100), api.calls)
            assertEquals(1, vm.uiState.value.songs.size)
            assertEquals(SearchTab.SONGS, vm.uiState.value.tab)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `loaded tab is not fetched again`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val api = FakeSearchApi()
            val vm = viewModel(api, dispatcher)
            vm.setQuery("test")
            vm.selectTab(SearchTab.ALBUMS)
            advanceUntilIdle()
            val afterFirst = api.calls.size

            // 再次选中同一 Tab → iOS `guard !loadedTabs.contains(tab)` 跳过
            vm.selectTab(SearchTab.ALBUMS)
            advanceUntilIdle()
            assertEquals(afterFirst, api.calls.size)

            // 手动 search() 也应被 loadedTabs 拦截
            vm.search()
            advanceUntilIdle()
            assertEquals(afterFirst, api.calls.size)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `changing query resets results and loaded tabs`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val vm = viewModel(FakeSearchApi(), dispatcher)
            vm.setQuery("first")
            vm.selectTab(SearchTab.SONGS)
            advanceUntilIdle()
            assertTrue(SearchTab.SONGS in vm.uiState.value.loadedTabs)
            assertEquals(1, vm.uiState.value.songs.size)

            vm.setQuery("second")
            assertTrue(vm.uiState.value.loadedTabs.isEmpty())
            assertTrue(vm.uiState.value.songs.isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `blank query does not perform search`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            var searchCalls = 0
            val api = object : NeteaseApi(NeteaseClient(InMemoryCookieStore())) {
                override suspend fun search(keywords: String, type: Int, limit: Int, offset: Int): SearchResult {
                    searchCalls++
                    return SearchResult()
                }
            }
            val vm = viewModel(api, dispatcher)
            vm.setQuery("   ")
            vm.search()
            advanceUntilIdle()

            assertEquals(0, searchCalls)
            assertTrue(vm.uiState.value.loadedTabs.isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `setQuery triggers suggestions`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val vm = viewModel(FakeSearchApi(), dispatcher)
            vm.setQuery("ab")
            advanceUntilIdle()

            val suggestions = vm.uiState.value.suggestions
            assertNotNull(suggestions)
            assertEquals(1, suggestions!!.songs?.size)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `currentEmpty reflects selected tab`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val vm = viewModel(FakeSearchApi(), dispatcher)
            // iOS currentEmpty: ALL → songs.isEmpty() && artists.isEmpty()
            assertTrue(vm.uiState.value.currentEmpty())

            vm.setQuery("test")
            vm.selectTab(SearchTab.PLAYLISTS)
            advanceUntilIdle()
            // 歌单 Tab 有数据 → 非空
            assertTrue(!vm.uiState.value.currentEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }
}