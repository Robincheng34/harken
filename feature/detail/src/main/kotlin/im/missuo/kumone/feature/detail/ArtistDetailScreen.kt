// Ported from kumone by missuo (LGPL-3.0)
//
// 歌手详情页（T22 `ArtistDetailScreen.kt`，iOS `ArtistDetailView` 1:1 移植）。
//
// 结构：DetailHeader（圆形头像 + 简介）+「热门歌曲」TrackListView + 「热门专辑」横滑。

package im.missuo.kumone.feature.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import im.missuo.kumone.design.theme.GlassTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.missuo.kumone.design.component.ErrorStateView
import im.missuo.kumone.design.component.SectionHeader
import im.missuo.kumone.design.component.TrackListView
import im.missuo.kumone.design.composition.LocalMusicController
import im.missuo.kumone.design.composition.LocalNeteaseApi
import im.missuo.kumone.design.composition.LocalPlaybackCommands
import im.missuo.kumone.design.composition.LocalToastState
import im.missuo.kumone.design.navigation.Destinations
import im.missuo.kumone.feature.detail.components.DetailHeader
import im.missuo.kumone.net.model.Formatters
import im.missuo.kumone.playback.PlayContext
import im.missuo.kumone.playback.PlaySource
import im.missuo.kumone.player.PlayerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 歌手详情。
 *
 * @param artistID 歌手 id（来自路由参数）。
 * @param onOpenAlbum 点击专辑封面跳转专辑详情。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistDetailScreen(
    artistID: Long,
    onOpenAlbum: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val api = LocalNeteaseApi.current ?: error("LocalNeteaseApi 必须在 KumoneApp 提供")
    val commands = LocalPlaybackCommands.current
    val toast = LocalToastState.current
    val scope = rememberCoroutineScope()

    val viewModel = remember(api) { DetailViewModel(api) }
    // 页面销毁时取消 ViewModel 内部协程（原 bug：自建 scope 永不取消，收集器常驻泄漏）。
    DisposableEffect(Unit) { onDispose { viewModel.cancel() } }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(artistID) { viewModel.loadArtist(artistID) }

    val controller = LocalMusicController.current
    val playerState by (controller?.state ?: remember { MutableStateFlow(PlayerState()) })
        .collectAsStateWithLifecycle()
    val currentTrackId: Long? = playerState.currentTrack?.id

    Scaffold(
        modifier = modifier,
        topBar = {
            GlassTopAppBar(
                title = {
                    Text(
                        text = uiState.header.title.ifEmpty {
                            stringResource(R.string.detail_artist)
                        },
                    )
                },
            )
        },
    ) { padding ->
        val context = PlayContext.artist(artistID, uiState.header.title)
        when {
            uiState.error != null && uiState.tracks.isEmpty() ->
                ErrorStateView(
                    message = uiState.error ?: stringResource(R.string.detail_load_failed),
                    onRetry = { viewModel.loadArtist(artistID) },
                    modifier = Modifier.padding(padding).fillMaxSize(),
                )

            uiState.isLoading && uiState.header.id == 0L ->
                Box(
                    modifier = Modifier.padding(padding).fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }

            else ->
                TrackListView(
                    tracks = uiState.tracks,
                    currentTrackId = currentTrackId,
                    onPlay = { _, index ->
                        scope.launch {
                            commands?.play(
                                tracks = uiState.tracks,
                                source = PlaySource.ARTIST,
                                context = context,
                                startIndex = index,
                            )
                        }
                    },
                    header = {
                        item(key = "detail-header") {
                            DetailHeader(
                                header = uiState.header,
                                onPlayAll = {
                                    scope.launch {
                                        val ok = commands?.playArtistHotTracks(
                                            artistID,
                                            uiState.header.title,
                                        ) ?: false
                                        if (!ok) toast.show("播放失败")
                                    }
                                },
                                onToggleSubscribe = viewModel::toggleArtistSubscription,
                            )
                        }
                        if (uiState.tracks.isNotEmpty()) {
                            item(key = "hot-songs-header") {
                                SectionHeader(title = stringResource(R.string.detail_hot_songs))
                            }
                        }
                        if (uiState.artistAlbums.isNotEmpty()) {
                            item(key = "albums-header") {
                                SectionHeader(title = stringResource(R.string.detail_hot_albums))
                            }
                            item(key = "albums-shelf") {
                                im.missuo.kumone.design.component.PlaylistShelf(
                                    title = "",
                                    items = uiState.artistAlbums.map { album ->
                                        im.missuo.kumone.net.model.PlaylistSummary(
                                            id = album.id,
                                            name = album.name,
                                            coverURL = album.picUrl,
                                        )
                                    },
                                    onOpen = onOpenAlbum,
                                    onPlay = { albumId ->
                                        scope.launch {
                                            commands?.playAlbum(albumId, albumId.toString())
                                        }
                                    },
                                    staggered = true,
                                )
                            }
                        }
                    },
                    modifier = Modifier.padding(padding).fillMaxSize(),
                )
        }
    }
}
