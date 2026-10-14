// Ported from kumone by missuo (LGPL-3.0)
//
// 详情页 ViewModel 单测（T21/T22 `DetailViewModelTest.kt`，纯 JVM）。
//
// 覆写 NeteaseApi 的详情接口注入确定性数据，验证三类详情的头部/曲目装配，
// 以及歌单 tracks 为空时按 trackIds 分批补齐。

package im.missuo.kumone.feature.detail

import im.missuo.kumone.net.api.NeteaseApi
import im.missuo.kumone.net.client.InMemoryCookieStore
import im.missuo.kumone.net.client.NeteaseClient
import im.missuo.kumone.net.model.AlbumDetail
import im.missuo.kumone.net.model.AlbumDetailResponse
import im.missuo.kumone.net.model.AlbumSummary
import im.missuo.kumone.net.model.ArtistResponse
import im.missuo.kumone.net.model.ArtistSummary
import im.missuo.kumone.net.model.PlaylistDetail
import im.missuo.kumone.net.model.PlaylistDetailResponse
import im.missuo.kumone.net.model.SongDetailResponse
import im.missuo.kumone.net.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModelTest {

    private fun track(id: Long, name: String = "song$id") = Track(id = id, name = name)

    private class FakeDetailApi(
        val playlist: PlaylistDetailResponse? = null,
        val album: AlbumDetailResponse? = null,
        val artist: ArtistResponse? = null,
        val artistAlbums: List<AlbumSummary> = emptyList(),
        val songsByBatch: Map<Int, List<Track>> = emptyMap(),
    ) : NeteaseApi(NeteaseClient(InMemoryCookieStore())) {
        override suspend fun playlistDetail(id: Long): PlaylistDetailResponse =
            playlist ?: error("no playlist fixture")

        override suspend fun album(id: Long): AlbumDetailResponse =
            album ?: error("no album fixture")

        override suspend fun artist(id: Long): ArtistResponse =
            artist ?: error("no artist fixture")

        override suspend fun artistAlbums(id: Long, limit: Int, offset: Int): List<AlbumSummary> =
            artistAlbums

        override suspend fun songDetails(ids: List<Long>): SongDetailResponse =
            SongDetailResponse(songs = songsByBatch[ids.size].orEmpty())
    }

    @Test
    fun `playlist detail maps header and tracks`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val api = FakeDetailApi(
                playlist = PlaylistDetailResponse(
                    playlist = PlaylistDetail(
                        id = 42,
                        name = "我的歌单",
                        trackCount = 2,
                        playCount = 1234,
                        tracks = listOf(track(1), track(2)),
                    ),
                ),
            )
            val vm = DetailViewModel(api, CoroutineScope(dispatcher))
            vm.loadPlaylist(42)
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(DetailKind.PLAYLIST, state.kind)
            assertEquals(42L, state.header.id)
            assertEquals("我的歌单", state.header.title)
            assertEquals(2, state.tracks.size)
            assertEquals(1234, state.header.playCount)
            assertTrue(state.tracksComplete)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `playlist detail backfills tracks from trackIds when tracks empty`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val api = FakeDetailApi(
                playlist = PlaylistDetailResponse(
                    playlist = PlaylistDetail(
                        id = 7,
                        name = "长歌单",
                        trackCount = 600,
                        trackIds = List(600) { im.missuo.kumone.net.model.TrackIDRef(it.toLong()) },
                        tracks = emptyList(),
                    ),
                ),
                songsByBatch = mapOf(500 to List(500) { track(it.toLong()) }),
            )
            val vm = DetailViewModel(api, CoroutineScope(dispatcher))
            vm.loadPlaylist(7)
            advanceUntilIdle()

            val state = vm.uiState.value
            // 首批 500 首补齐（第二批因 fake 只返回 size=500 的数据而为空）。
            assertEquals(500, state.tracks.size)
            assertTrue(state.tracksComplete)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `album detail maps header and songs`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val api = FakeDetailApi(
                album = AlbumDetailResponse(
                    album = AlbumDetail(id = 9, name = "专辑九", size = 1),
                    songs = listOf(track(100)),
                ),
            )
            val vm = DetailViewModel(api, CoroutineScope(dispatcher))
            vm.loadAlbum(9)
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(DetailKind.ALBUM, state.kind)
            assertEquals("专辑九", state.header.title)
            assertEquals(1, state.tracks.size)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `artist detail maps hot songs and albums`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val api = FakeDetailApi(
                artist = ArtistResponse(
                    artist = ArtistSummary(id = 3, name = "歌手三", musicSize = 1),
                    hotSongs = listOf(track(200)),
                ),
                artistAlbums = listOf(AlbumSummary(id = 5, name = "专辑五")),
            )
            val vm = DetailViewModel(api, CoroutineScope(dispatcher))
            vm.loadArtist(3)
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(DetailKind.ARTIST, state.kind)
            assertEquals("歌手三", state.header.title)
            assertEquals(1, state.tracks.size)
            assertEquals(1, state.artistAlbums.size)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `detail surfaces error when api throws`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val vm = DetailViewModel(FakeDetailApi(), CoroutineScope(dispatcher))
            vm.loadPlaylist(1)
            advanceUntilIdle()

            val state = vm.uiState.value
            assertTrue(state.error != null)
            assertTrue(!state.isLoading)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
