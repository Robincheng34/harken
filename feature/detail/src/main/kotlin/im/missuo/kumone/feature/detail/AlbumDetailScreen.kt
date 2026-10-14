// Ported from kumone by missuo (LGPL-3.0)
//
// 专辑详情页（T22 `AlbumDetailScreen.kt`，iOS `AlbumDetailView` 1:1 移植）。
//
// 结构与歌单详情一致：DetailHeader + TrackListView；播放全部走
// `PlaybackCommands.playAlbum`（内部取详情后成队列）。

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
import im.missuo.kumone.design.component.TrackListView
import im.missuo.kumone.design.composition.LocalMusicController
import im.missuo.kumone.design.composition.LocalNeteaseApi
import im.missuo.kumone.design.composition.LocalPlaybackCommands
import im.missuo.kumone.feature.detail.components.DetailHeader
import im.missuo.kumone.playback.PlayContext
import im.missuo.kumone.playback.PlaySource
import im.missuo.kumone.player.PlayerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 专辑详情。
 *
 * @param albumID 专辑 id（来自路由参数）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    albumID: Long,
    modifier: Modifier = Modifier,
) {
    val api = LocalNeteaseApi.current ?: error("LocalNeteaseApi 必须在 KumoneApp 提供")
    val commands = LocalPlaybackCommands.current
    val scope = rememberCoroutineScope()

    val viewModel = remember(api) { DetailViewModel(api) }
    // 页面销毁时取消 ViewModel 内部协程（原 bug：自建 scope 永不取消，收集器常驻泄漏）。
    DisposableEffect(Unit) { onDispose { viewModel.cancel() } }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(albumID) { viewModel.loadAlbum(albumID) }

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
                            stringResource(R.string.detail_album)
                        },
                    )
                },
            )
        },
    ) { padding ->
        val context = PlayContext.album(albumID, uiState.header.title)
        when {
            uiState.error != null && uiState.tracks.isEmpty() ->
                ErrorStateView(
                    message = uiState.error ?: stringResource(R.string.detail_load_failed),
                    onRetry = { viewModel.loadAlbum(albumID) },
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
                                source = PlaySource.ALBUM,
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
                                        commands?.play(
                                            tracks = uiState.tracks,
                                            source = PlaySource.ALBUM,
                                            context = context,
                                            startIndex = 0,
                                        )
                                    }
                                },
                                onToggleSubscribe = viewModel::toggleAlbumSubscription,
                            )
                        }
                    },
                    modifier = Modifier.padding(padding).fillMaxSize(),
                )
        }
    }
}
