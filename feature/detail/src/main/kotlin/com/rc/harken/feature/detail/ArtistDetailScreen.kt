// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 歌手详情页（T22 `ArtistDetailScreen.kt`，iOS `ArtistDetailView` 1:1 移植）。
//
// 结构：DetailHeader（圆形头像 + 简介）+「热门歌曲」TrackListView + 「热门专辑」横滑。

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
import com.rc.harken.design.component.SectionHeader
import com.rc.harken.design.component.TrackListView
import com.rc.harken.design.composition.LocalMusicController
import com.rc.harken.design.composition.LocalNeteaseApi
import com.rc.harken.design.composition.LocalPlaybackCommands
import com.rc.harken.design.composition.LocalToastState
import com.rc.harken.design.navigation.Destinations
import com.rc.harken.feature.detail.components.DetailHeader
import com.rc.harken.net.model.Formatters
import com.rc.harken.playback.PlayContext
import com.rc.harken.playback.PlaySource
import com.rc.harken.player.PlayerState
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
    onBack: () -> Unit,
    onOpenAlbum: (Long) -> Unit,
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

    LaunchedEffect(artistID) { viewModel.loadArtist(artistID) }

    // 关注失败必须有可见反馈（原 bug：`runCatching` 吞异常，按钮点了永远不变）。
    val subscribeFailedMessage = stringResource(R.string.detail_subscribe_failed)
    LaunchedEffect(viewModel, subscribeFailedMessage) {
        viewModel.subscribeFailures.collect { toast.show(subscribeFailedMessage) }
    }

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
                                        // 失败提示由 :core:player 的 PlaybackCommands 统一上抛
                                        // （player_command_failed），这里不再重复 toast，
                                        // 否则一次失败会连弹两条。
                                        commands?.playArtistHotTracks(
                                            artistID,
                                            uiState.header.title,
                                        )
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
                                com.rc.harken.design.component.PlaylistShelf(
                                    title = "",
                                    items = uiState.artistAlbums.map { album ->
                                        com.rc.harken.net.model.PlaylistSummary(
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
                                    // 上面已有「热门专辑」SectionHeader，这里再自绘一遍会多出
                                    // 一条空白标题行 + 一个多余「>」箭头（与搜索页同款缺陷）。
                                    showHeader = false,
                                )
                            }
                        }
                    },
                    modifier = Modifier.padding(padding).fillMaxSize(),
                )
        }
    }
}
