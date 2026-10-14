// Ported from kumone by missuo (LGPL-3.0)
//
// 全屏播放页（官方客户端布局，T26/T27 `NowPlayingScreen.kt`）。
//
// 结构（对照官方客户端参照图，自顶向下）：
//  1. 模糊封面全屏背景（`ArtworkBlurBackground`，API 31+ 高斯模糊）；
//  2. 顶栏：收起箭头 + 来源名（`PlayContext.name`）+ 分享；
//  3. 半透明白胶唱片（`VinylRecordView`，播放旋转；点击切换歌词面板）；
//  4. 社交行：曲名 + 歌手 + 关注 pill + 红心 + 评论；
//  5. 控制条（`NowPlayingControls`：进度 + 音质标签 + 单行五键）；
//  6. 底部五图标行：音效 / 歌词 / 歌曲信息 / 下载 / 更多。
//
// 队列为底部上拉二级页面（`QueuePanel`）；歌词面板与队列互斥。

package im.missuo.kumone.feature.player

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.missuo.kumone.design.composition.LocalIsLoggedIn
import im.missuo.kumone.design.composition.LocalMusicController
import im.missuo.kumone.design.composition.LocalNeteaseApi
import im.missuo.kumone.design.composition.LocalSettingsRepository
import im.missuo.kumone.design.composition.LocalToastState
import im.missuo.kumone.design.theme.GlassChip
import im.missuo.kumone.design.theme.bright
import im.missuo.kumone.design.theme.glassTokens
import im.missuo.kumone.feature.player.components.ArtworkBlurBackground
import im.missuo.kumone.feature.player.components.LyricsPanel
import im.missuo.kumone.feature.player.components.NowPlayingControls
import im.missuo.kumone.feature.player.components.QueuePanel
import im.missuo.kumone.feature.player.components.VinylRecordView
import im.missuo.kumone.net.model.Formatters
import im.missuo.kumone.net.model.Track
import im.missuo.kumone.playback.RepeatMode
import kotlinx.coroutines.flow.MutableStateFlow

/** 全屏播放页覆盖层路由（与 `:app` `Routes.NOW_PLAYING` 的字符串契约）。 */
const val NOW_PLAYING_ROUTE = "now_playing"

/** 官方红心红（点赞激活色）。 */
private val LikeRed = Color(0xFFE8434F)

/**
 * 全屏播放页（官方客户端布局）。
 *
 * @param onBack 返回（收起播放页）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val controller = LocalMusicController.current
        ?: error("LocalMusicController 必须在 KumoneApp 提供")
    val api = LocalNeteaseApi.current ?: error("LocalNeteaseApi 必须在 KumoneApp 提供")
    val settingsRepo = LocalSettingsRepository.current
    val toast = LocalToastState.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    val isLoggedIn by LocalIsLoggedIn.current.collectAsStateWithLifecycle()
    val state by controller.state.collectAsStateWithLifecycle()

    val viewModel = remember { NowPlayingViewModel() }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 社交状态（红心 / 关注）：进程级存储，种子一次、失败 toast。
    val likedTrackIDs by NowPlayingSocialStore.likedTrackIDs.collectAsStateWithLifecycle()
    val followingArtistIDs by NowPlayingSocialStore.followingArtistIDs.collectAsStateWithLifecycle()
    LaunchedEffect(api, isLoggedIn) {
        // seed 使用 Store 内部进程级 scope（原 bug：挂组合级 scope，中途离开
        // 播放页协程被取消且 seeded 已置 true，社交状态本进程内永不加载）。
        if (isLoggedIn) NowPlayingSocialStore.seed(api)
    }
    LaunchedEffect(Unit) {
        NowPlayingSocialStore.errors.collect { toast.show(it) }
    }

    val settings by (settingsRepo?.settings
        ?: remember { MutableStateFlow(im.missuo.kumone.storage.settings.AppSettings()) })
        .collectAsStateWithLifecycle(initialValue = im.missuo.kumone.storage.settings.AppSettings())

    val track = state.currentTrack
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    // 音质标签：优先当前曲实际服务音质，回落设置档位。
    val qualityLabel = stringResource(
        R.string.player_quality_tag,
        when (state.servedQuality ?: settings.audioQuality.apiLevel) {
            "standard" -> stringResource(R.string.player_quality_standard)
            "higher" -> stringResource(R.string.player_quality_higher)
            "lossless" -> stringResource(R.string.player_quality_lossless)
            "hires" -> stringResource(R.string.player_quality_hires)
            else -> stringResource(R.string.player_quality_exhigh)
        },
    )

    // 分享（官方顶栏右侧）：文本意图带歌名 + 歌手 + 官网链接。
    val shareChooserTitle = stringResource(R.string.player_share_chooser)
    fun shareCurrentTrack() {
        val current = track ?: return
        val url = "https://music.163.com/song?id=${current.id}"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(
                Intent.EXTRA_TEXT,
                "${current.name} - ${current.artistNames}\n$url",
            )
        }
        context.startActivity(Intent.createChooser(intent, shareChooserTitle))
    }

    // 播放模式四态轮换（官方客户端单键）：顺序 → 随机 → 列表循环 → 单曲循环。
    val onCyclePlayMode = {
        when {
            state.shuffleEnabled -> {
                controller.setShuffle(false)
                controller.setRepeatMode(RepeatMode.ALL)
            }
            state.repeatMode == RepeatMode.ALL -> controller.setRepeatMode(RepeatMode.ONE)
            state.repeatMode == RepeatMode.ONE -> controller.setRepeatMode(RepeatMode.OFF)
            else -> controller.setShuffle(true)
        }
    }

    ArtworkBlurBackground(
        coverURL = track?.album?.picUrl,
        modifier = modifier,
    ) {
        // 触摸屏障：播放页铺满全屏，必须消费掉自身范围内「子组件未消费」的触摸。
        // 否则非交互区（社交行空白、控制条间隙、五图标行间隙）的点击会按
        // Compose 重叠兄弟节点的命中路径继续向下分发，落到下层首页的
        // 排行榜卡片上（实测：点播放页空白处会打开背后的歌单详情）。
        // 屏障放在页面根 Box：主派发阶段子组件（更深）先消费，未消费的
        // 才由本屏障收掉，既不挡页面内交互，也断掉向下穿透。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            if (event.changes.none { it.isConsumed }) {
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 1. 顶栏：收起箭头 + 来源名 + 分享
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlassChip(
                        onClick = onBack,
                        modifier = Modifier.size(36.dp),
                        tokens = glassTokens().bright(),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = "收起",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Text(
                        text = state.context?.name ?: stringResource(R.string.player_playing),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.9f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .weight(1f),
                    )
                    if (state.isTrial) {
                        Text(
                            text = stringResource(R.string.player_trial),
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    GlassChip(
                        onClick = { shareCurrentTrack() },
                        modifier = Modifier.size(36.dp),
                        tokens = glassTokens().bright(),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = stringResource(R.string.player_share),
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }

                // 2. 唱片 / 歌词切换区
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = viewModel::toggleLyricsPanel,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (uiState.showLyricsPanel) {
                        LyricsPanel(
                            trackName = track?.name ?: "",
                            artistName = track?.artistNames ?: "",
                            lyrics = state.lyrics,
                            positionSeconds = state.positionMs / 1000.0,
                            showTranslation = settings.showTranslation,
                            verbatimLyrics = settings.verbatimLyrics,
                            isScrubbing = uiState.isLyricsScrubbing,
                            onSeekTo = { seconds -> controller.seekTo((seconds * 1000).toLong()) },
                            onScrubbingChange = viewModel::setLyricsScrubbing,
                            onCollapse = viewModel::toggleLyricsPanel,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        VinylRecordView(
                            coverURL = track?.album?.picUrl,
                            size = (screenHeight * 0.40f).coerceIn(220.dp, 340.dp),
                            isPlaying = state.isPlaying,
                        )
                    }
                }

                // 3. 社交行：曲名 + 歌手 + 关注 + 红心 + 评论
                if (track != null) {
                    TrackSocialRow(
                        track = track,
                        isLiked = likedTrackIDs?.contains(track.id) == true,
                        isFollowing = track.artists.firstOrNull()?.id
                            ?.let { followingArtistIDs?.contains(it) == true } == true,
                        onFollow = { artistId ->
                            if (isLoggedIn) {
                                NowPlayingSocialStore.toggleFollow(api, scope, artistId)
                            } else {
                                toast.show(context.getString(R.string.player_login_required))
                            }
                        },
                        onLike = {
                            if (isLoggedIn) {
                                NowPlayingSocialStore.toggleLike(api, scope, track.id)
                            } else {
                                toast.show(context.getString(R.string.player_login_required))
                            }
                        },
                        onComment = {
                            toast.show(context.getString(R.string.player_comment_unsupported))
                        },
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 4. 控制条：进度 + 音质标签 + 单行五键
                NowPlayingControls(
                    isPlaying = state.isPlaying,
                    isFMMode = state.isFMMode,
                    repeatMode = state.repeatMode,
                    shuffleEnabled = state.shuffleEnabled,
                    positionMs = state.positionMs,
                    durationMs = state.durationMs,
                    qualityLabel = qualityLabel,
                    onTogglePlay = controller::togglePlay,
                    onNext = controller::next,
                    onPrevious = controller::previous,
                    onTrash = controller::fmTrash,
                    onSeek = controller::seekTo,
                    onCyclePlayMode = onCyclePlayMode,
                    onToggleQueue = viewModel::toggleQueuePanel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 5. 底部五图标行：音效 / 歌词 / 歌曲信息 / 下载 / 更多
                PlayerSecondaryBar(
                    lyricsActive = uiState.showLyricsPanel,
                    onSoundEffect = {
                        toast.show(context.getString(R.string.player_sound_effect_unsupported))
                    },
                    onLyrics = viewModel::toggleLyricsPanel,
                    onSongInfo = viewModel::toggleSongInfo,
                    onDownload = {
                        toast.show(context.getString(R.string.player_download_unsupported))
                    },
                    onMore = viewModel::toggleMoreMenu,
                    moreMenu = {
                        // 更多菜单（锚定「更多」项，空间不足时自动上翻）
                        DropdownMenu(
                            expanded = uiState.showMoreMenu,
                            onDismissRequest = viewModel::toggleMoreMenu,
                            modifier = Modifier.background(Color(0xFF1C1C1E).copy(alpha = 0.92f)),
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(R.string.player_copy_link),
                                        color = Color.White,
                                    )
                                },
                                onClick = {
                                    viewModel.toggleMoreMenu()
                                    track?.let {
                                        clipboard.setText(
                                            AnnotatedString(
                                                "https://music.163.com/song?id=${it.id}",
                                            ),
                                        )
                                    }
                                    toast.show(context.getString(R.string.player_link_copied))
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(R.string.player_share),
                                        color = Color.White,
                                    )
                                },
                                onClick = {
                                    viewModel.toggleMoreMenu()
                                    shareCurrentTrack()
                                },
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // 歌曲信息弹窗
            if (uiState.showSongInfo && track != null) {
                TrackInfoDialog(
                    track = track,
                    qualityLabel = qualityLabel,
                    onDismiss = viewModel::toggleSongInfo,
                )
            }

            // 队列二级页面（从底部上拉；点击遮罩任意位置或下滑取消）
            if (uiState.showQueuePanel) {
                val source = playSourceOf(state.context)
                ModalBottomSheet(
                    onDismissRequest = viewModel::toggleQueuePanel,
                    containerColor = Color(0xFF1C1C1E).copy(alpha = 0.92f),
                    contentColor = Color.White,
                ) {
                    QueuePanel(
                        tracks = upcoming(state),
                        totalCount = state.queue.size,
                        onPlayAt = { index ->
                            val offset = state.currentIndex + 1 + index
                            controller.play(state.queue, offset, state.context, source)
                        },
                        onClearQueue = { controller.clearQueue() },
                    )
                }
            }
        }
    }
}

/**
 * 社交行（官方客户端）：曲名 + 歌手 + 关注 pill + 红心 + 评论。
 */
@Composable
private fun TrackSocialRow(
    track: Track,
    isLiked: Boolean,
    isFollowing: Boolean,
    onFollow: (Long) -> Unit,
    onLike: () -> Unit,
    onComment: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.name,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artistNames.ifEmpty { "Kumone" },
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.62f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // 关注 pill（无歌手信息时不展示）
        val artistId = track.artists.firstOrNull()?.id ?: 0L
        if (artistId > 0) {
            GlassChip(
                onClick = { onFollow(artistId) },
                shape = RoundedCornerShape(14.dp),
                tokens = glassTokens().bright().let {
                    if (isFollowing) it.copy(fill = Color.White.copy(alpha = 0.08f)) else it
                },
                modifier = Modifier.padding(end = 4.dp),
            ) {
                Text(
                    text = stringResource(
                        if (isFollowing) R.string.player_following else R.string.player_follow,
                    ),
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = if (isFollowing) 0.55f else 0.9f),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
        }

        // 红心（点赞 / 取消点赞）
        IconButton(onClick = onLike, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = if (isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = stringResource(R.string.player_like),
                tint = if (isLiked) LikeRed else Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(21.dp),
            )
        }

        // 评论（官方客户端入口；后端评论 API 未接入，点击提示）
        IconButton(onClick = onComment, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Filled.ChatBubbleOutline,
                contentDescription = stringResource(R.string.player_comment),
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * 底部五图标行（官方客户端）：音效 / 歌词 / 歌曲信息 / 下载 / 更多。
 *
 * @param moreMenu 「更多」项的下拉菜单内容（由调用方提供，锚定在「更多」项上）。
 */
@Composable
private fun PlayerSecondaryBar(
    lyricsActive: Boolean,
    onSoundEffect: () -> Unit,
    onLyrics: () -> Unit,
    onSongInfo: () -> Unit,
    onDownload: () -> Unit,
    onMore: () -> Unit,
    moreMenu: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SecondaryItem(
            icon = Icons.Filled.GraphicEq,
            label = stringResource(R.string.player_sound_effect),
            onClick = onSoundEffect,
        )
        SecondaryItem(
            icon = Icons.Filled.MusicNote,
            label = stringResource(R.string.player_lyrics),
            active = lyricsActive,
            onClick = onLyrics,
        )
        SecondaryItem(
            icon = Icons.Filled.Info,
            label = stringResource(R.string.player_song_info),
            onClick = onSongInfo,
        )
        SecondaryItem(
            icon = Icons.Filled.Download,
            label = stringResource(R.string.player_download),
            onClick = onDownload,
        )
        // 「更多」：菜单锚定在此项（Box 包裹使 DropdownMenu 以该项为锚）。
        Box {
            SecondaryItem(
                icon = Icons.Filled.MoreHoriz,
                label = stringResource(R.string.player_more),
                onClick = onMore,
            )
            moreMenu()
        }
    }
}

/** 五图标行的单项（图标 + 小字标签，激活态纯白）。 */
@Composable
private fun SecondaryItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) Color.White else Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(21.dp),
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            color = if (active) Color.White else Color.White.copy(alpha = 0.7f),
        )
    }
}

/** 歌曲信息弹窗（本地元数据，无网络依赖）。 */
@Composable
private fun TrackInfoDialog(
    track: Track,
    qualityLabel: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1C1C1E).copy(alpha = 0.92f),
        titleContentColor = Color.White,
        textContentColor = Color.White.copy(alpha = 0.85f),
        title = { Text(text = stringResource(R.string.player_info_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoRow(stringResource(R.string.player_info_song), track.name)
                InfoRow(stringResource(R.string.player_info_artist), track.artistNames)
                InfoRow(stringResource(R.string.player_info_album), track.album.name)
                InfoRow(
                    stringResource(R.string.player_info_duration),
                    Formatters.duration(track.durationSeconds),
                )
                InfoRow(stringResource(R.string.player_info_quality), qualityLabel)
                InfoRow(stringResource(R.string.player_info_id), track.id.toString())
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "OK", color = Color.White)
            }
        },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.width(64.dp),
        )
        Text(
            text = value,
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.85f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 即将播放（当前曲之后）。 */
private fun upcoming(state: im.missuo.kumone.player.PlayerState) =
    if (state.currentIndex < 0) {
        state.queue
    } else {
        state.queue.drop(state.currentIndex + 1)
    }

/** 由 `PlayContext.Kind` 反推 `PlaySource`（队列重放时保持来源一致）。 */
private fun playSourceOf(context: im.missuo.kumone.playback.PlayContext?):
    im.missuo.kumone.playback.PlaySource = when (context?.kind) {
    im.missuo.kumone.playback.PlayContext.Kind.ALBUM -> im.missuo.kumone.playback.PlaySource.ALBUM
    im.missuo.kumone.playback.PlayContext.Kind.ARTIST -> im.missuo.kumone.playback.PlaySource.ARTIST
    im.missuo.kumone.playback.PlayContext.Kind.DAILY -> im.missuo.kumone.playback.PlaySource.DAILY
    im.missuo.kumone.playback.PlayContext.Kind.CLOUD -> im.missuo.kumone.playback.PlaySource.CLOUD
    im.missuo.kumone.playback.PlayContext.Kind.PLAYLIST,
    im.missuo.kumone.playback.PlayContext.Kind.HEARTBEAT,
    -> im.missuo.kumone.playback.PlaySource.PLAYLIST
    else -> im.missuo.kumone.playback.PlaySource.NONE
}
