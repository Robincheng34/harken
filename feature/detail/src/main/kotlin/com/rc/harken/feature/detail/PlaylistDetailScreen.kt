// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 歌单详情页（T21 `PlaylistDetailScreen.kt`，iOS `PlaylistDetailView` 1:1 移植）。
//
// 结构：DetailHeader（大封面 + 播放全部 + 收藏）+ TrackListView（当前曲高亮，点击即播）。

package com.rc.harken.feature.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.rc.harken.design.theme.GlassTopAppBar
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
import com.rc.harken.design.component.ErrorStateView
import com.rc.harken.design.component.TrackListView
import com.rc.harken.design.composition.LocalMusicController
import com.rc.harken.design.composition.LocalNeteaseApi
import com.rc.harken.design.composition.LocalPlaybackCommands
import com.rc.harken.design.composition.LocalToastState
import com.rc.harken.feature.detail.components.DetailHeader
import com.rc.harken.playback.PlayContext
import com.rc.harken.playback.PlaySource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 歌单详情。
 *
 * @param playlistID 歌单 id（来自路由参数）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    playlistID: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val api = LocalNeteaseApi.current ?: error("LocalNeteaseApi 必须在 HarkenApp 提供")
    val commands = LocalPlaybackCommands.current
    val toast = LocalToastState.current
    val scope = rememberCoroutineScope()

    // 系统返回：本页显式接管，避免被外层 Tab 的常驻 BackHandler 抢走。
    BackHandler(enabled = true) { onBack() }

    val viewModel = remember(api) { DetailViewModel(api) }
    // 页面销毁时取消 ViewModel 内部协程（原 bug：自建 scope 永不取消，收集器常驻泄漏）。
    DisposableEffect(Unit) { onDispose { viewModel.cancel() } }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(playlistID) { viewModel.loadPlaylist(playlistID) }

    // 收藏失败必须有可见反馈（原 bug：失败完全静默，按钮点了没变化也没提示）。
    val subscribeFailedMessage = stringResource(R.string.detail_subscribe_failed)
    LaunchedEffect(viewModel, subscribeFailedMessage) {
        viewModel.subscribeFailures.collect { toast.show(subscribeFailedMessage) }
    }

    // 当前播放曲（列表高亮）。
    val controller = LocalMusicController.current
    val playerState by (controller?.state ?: remember { MutableStateFlow(com.rc.harken.player.PlayerState()) })
        .collectAsStateWithLifecycle()
    val currentTrackId: Long? = playerState.currentTrack?.id

    Scaffold(
        modifier = modifier,
        topBar = {
            GlassTopAppBar(
                title = {
                    Text(
                        text = uiState.header.title.ifEmpty {
                            stringResource(R.string.detail_playlist)
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.detail_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        val context = PlayContext.playlist(playlistID, uiState.header.title)
        when {
            uiState.error != null && uiState.tracks.isEmpty() ->
                ErrorStateView(
                    message = uiState.error ?: stringResource(R.string.detail_load_failed),
                    onRetry = { viewModel.loadPlaylist(playlistID) },
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
                                source = PlaySource.PLAYLIST,
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
                                            source = PlaySource.PLAYLIST,
                                            context = context,
                                            startIndex = 0,
                                        )
                                    }
                                },
                                onToggleSubscribe = viewModel::togglePlaylistSubscription,
                            )
                        }
                    },
                    modifier = Modifier.padding(padding).fillMaxSize(),
                )
        }
    }
}
