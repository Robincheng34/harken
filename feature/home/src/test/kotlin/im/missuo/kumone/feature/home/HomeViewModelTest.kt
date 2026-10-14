// Ported from kumone by missuo (LGPL-3.0)
//
// 首页 ViewModel 单测（T18 `HomeViewModelTest.kt`，纯 JVM）。
//
// 通过覆写 NeteaseApi 的目录接口注入确定性数据，验证：未登录只拉取个性化歌单 +
// 官方排行榜（雷达为空）；登录态追加雷达歌单；取数全失败时回退 error 且结束 loading。

package im.missuo.kumone.feature.home

import im.missuo.kumone.net.api.NeteaseApi
import im.missuo.kumone.net.client.InMemoryCookieStore
import im.missuo.kumone.net.client.NeteaseClient
import im.missuo.kumone.net.model.AlbumSummary
import im.missuo.kumone.net.model.ArtistSummary
import im.missuo.kumone.net.model.PlaylistBriefBody
import im.missuo.kumone.net.model.PlaylistSummary
import im.missuo.kumone.net.model.ToplistItem
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private class FakeHomeApi(
        private val recommend: List<PlaylistSummary> = emptyList(),
        private val personalized: List<PlaylistSummary> = emptyList(),
        private val daily: List<Track> = emptyList(),
        private val toplists: List<ToplistItem> = emptyList(),
        private val albums: List<AlbumSummary> = emptyList(),
        private val artists: List<ArtistSummary> = emptyList(),
        private val radar: Map<Long, String> = emptyMap(),
    ) : NeteaseApi(NeteaseClient(InMemoryCookieStore())) {
        override suspend fun recommendResource(): List<PlaylistSummary> = recommend
        override suspend fun personalizedPlaylists(limit: Int): List<PlaylistSummary> = personalized
        override suspend fun dailyRecommendSongs(): List<Track> = daily
        override suspend fun toplists(): List<ToplistItem> = toplists
        override suspend fun newAlbums(area: String, limit: Int, offset: Int): List<AlbumSummary> = albums
        override suspend fun topArtists(limit: Int): List<ArtistSummary> = artists
        override suspend fun playlistBrief(id: Long): PlaylistBriefBody =
            PlaylistBriefBody(id = id, name = radar[id], coverImgUrl = "http://cover/$id")
    }

    private fun viewModel(api: NeteaseApi, dispatcher: TestDispatcher): HomeViewModel =
        HomeViewModel(api, CoroutineScope(dispatcher))

    @Test
    fun `load as guest fetches personalized playlists and official toplists`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val api = FakeHomeApi(
                personalized = listOf(
                    PlaylistSummary(id = 2L, name = "p1"),
                    PlaylistSummary(id = 3L, name = "p2"),
                ),
                toplists = listOf(
                    ToplistItem(id = 19_723_756L, name = "飙升榜"),
                    ToplistItem(id = 3_779_629L, name = "新歌榜"),
                    ToplistItem(id = 999L, name = "非官方榜"),
                ),
                albums = listOf(AlbumSummary(id = 1L, name = "a1")),
                artists = listOf(ArtistSummary(id = 1L, name = "ar1")),
            )
            val vm = viewModel(api, dispatcher)
            vm.load(loggedIn = false)
            advanceUntilIdle()

            val state = vm.uiState.value
            assertFalse(state.isLoading)
            assertEquals(2, state.recommendPlaylists.size)
            // 仅官方榜被保留（999 被过滤）。
            assertEquals(2, state.toplists.size)
            assertEquals(1, state.newAlbums.size)
            assertEquals(1, state.artists.size)
            // 未登录态无雷达歌单。
            assertTrue(state.radarPlaylists.isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `load as logged in appends radar playlists`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val radar = mapOf(
                3_136_952_023L to "私人雷达|为你推荐",
                2_829_883_282L to "场景雷达|私人漫游",
            )
            val api = FakeHomeApi(
                recommend = listOf(PlaylistSummary(id = 1L, name = "r1")),
                personalized = listOf(
                    PlaylistSummary(id = 2L, name = "p1"),
                    PlaylistSummary(id = 3L, name = "p2"),
                ),
                toplists = listOf(ToplistItem(id = 19_723_756L, name = "飙升榜")),
                radar = radar,
            )
            val vm = viewModel(api, dispatcher)
            vm.load(loggedIn = true)
            advanceUntilIdle()

            val state = vm.uiState.value
            assertFalse(state.isLoading)
            // 登录态：head(recommend) + tail(personalized) 去重 = 3。
            assertEquals(3, state.recommendPlaylists.size)
            // 两个雷达 id 被拉取，标题按「|」拆分取末段。
            assertEquals(2, state.radarPlaylists.size)
            assertEquals("为你推荐", state.radarPlaylists[0].title)
            assertEquals("私人雷达", state.radarPlaylists[0].subtitle)
            assertNotNull(state.radarPlaylists[0].coverURL)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `load surfaces error when all fetches fail`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val api = object : NeteaseApi(NeteaseClient(InMemoryCookieStore())) {
                override suspend fun recommendResource(): List<PlaylistSummary> =
                    throw RuntimeException("network down")
                override suspend fun personalizedPlaylists(limit: Int): List<PlaylistSummary> =
                    throw RuntimeException("network down")
                override suspend fun dailyRecommendSongs(): List<Track> =
                    throw RuntimeException("network down")
                override suspend fun toplists(): List<ToplistItem> = throw RuntimeException("boom")
                override suspend fun newAlbums(area: String, limit: Int, offset: Int): List<AlbumSummary> =
                    throw RuntimeException("boom")
                override suspend fun topArtists(limit: Int): List<ArtistSummary> =
                    throw RuntimeException("boom")
            }
            val vm = viewModel(api, dispatcher)
            vm.load(loggedIn = false)
            advanceUntilIdle()

            val state = vm.uiState.value
            assertFalse(state.isLoading)
            assertNotNull(state.error)
            assertTrue(state.recommendPlaylists.isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }
}
