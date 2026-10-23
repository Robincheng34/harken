// Ported from the upstream LGPL-3.0 project (see NOTICE)
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

package com.rc.harken.feature.player

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import com.rc.harken.design.composition.LocalIsLoggedIn
import com.rc.harken.design.theme.AppAnimations
import com.rc.harken.design.composition.LocalMusicController
import com.rc.harken.design.composition.LocalNeteaseApi
import com.rc.harken.design.composition.LocalDownloadRepository
import com.rc.harken.design.composition.LocalSettingsRepository
import com.rc.harken.design.composition.LocalToastState
import com.rc.harken.feature.player.components.ArtworkBlurBackground
import com.rc.harken.feature.player.components.LyricsPanel
import com.rc.harken.feature.player.components.NowPlayingControls
import com.rc.harken.feature.player.components.QueuePanel
import com.rc.harken.feature.player.components.VinylRecordView
import com.rc.harken.net.model.Formatters
import com.rc.harken.net.model.Track
import com.rc.harken.playback.AudioQuality
import com.rc.harken.playback.RepeatMode
import com.rc.harken.storage.download.DownloadStatus
import com.rc.harken.storage.settings.NowPlayingMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** 全屏播放页覆盖层路由（与 `:app` `Routes.NOW_PLAYING` 的字符串契约）。 */
const val NOW_PLAYING_ROUTE = "now_playing"

/** 官方红心红（点赞激活色）。 */
private val LikeRed = Color(0xFFE8434F)

/** 播放页中心区内容模式（封面 ⇄ 歌词 AnimatedContent 状态）。 */
private enum class CenterMode { Lyrics, Cover, Vinyl }

/**
 * 全屏播放页（官方客户端布局）。
 *
 * @param onBack 返回（收起播放页）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    onBack: () -> Unit,
    onOpenSongInfo: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val controller = LocalMusicController.current
        ?: error("LocalMusicController 必须在 HarkenApp 提供")
    val api = LocalNeteaseApi.current ?: error("LocalNeteaseApi 必须在 HarkenApp 提供")
    val settingsRepo = LocalSettingsRepository.current
    val toast = LocalToastState.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val downloadRepo = LocalDownloadRepository.current
    var showQualitySheet by remember { mutableStateOf(false) }

    val isLoggedIn by LocalIsLoggedIn.current.collectAsStateWithLifecycle()
    val state by controller.state.collectAsStateWithLifecycle()

    val viewModel = remember { NowPlayingViewModel() }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 社交状态（红心）：进程级存储，种子一次、失败 toast。
    val likedTrackIDs by NowPlayingSocialStore.likedTrackIDs.collectAsStateWithLifecycle()
    LaunchedEffect(api, isLoggedIn) {
        // seed 使用 Store 内部进程级 scope（原 bug：挂组合级 scope，中途离开
        // 播放页协程被取消且 seeded 已置 true，社交状态本进程内永不加载）。
        if (isLoggedIn) NowPlayingSocialStore.seed(api)
    }
    LaunchedEffect(Unit) {
        NowPlayingSocialStore.errors.collect { toast.show(it) }
    }
    // 下载事件 → toast：下载本身跑在 repo 进程级 scope，离开播放页也不中断；
    // 这里只订阅事件总线做 UI 反馈，与点击/页面生命周期完全解耦。
    LaunchedEffect(Unit) {
        downloadRepo?.bus?.collect { status ->
            when (status) {
                is DownloadStatus.Started -> toast.show("正在下载：${status.title}")
                is DownloadStatus.Completed -> toast.show("已下载：${status.title}")
                is DownloadStatus.AlreadyExists -> toast.show("${status.title}：该音质已下载")
                is DownloadStatus.Failed -> toast.show("下载失败：${status.reason}")
                is DownloadStatus.Progress -> {} // 进度不刷 toast，避免刷屏
            }
        }
    }

    // 歌词显示且未沉浸时，用户 5 秒无操作则自动进入全屏沉浸歌词模式。
    // 任意触摸都会经指针屏障 [markInteraction] 重置计时；进入沉浸后本计时停止。
    LaunchedEffect(uiState.showLyricsPanel, uiState.isLyricsImmersive) {
        if (!uiState.showLyricsPanel || uiState.isLyricsImmersive) return@LaunchedEffect
        while (true) {
            delay(250)
            if (System.currentTimeMillis() - viewModel.lastInteractionMs >= 5000) {
                viewModel.enterLyricsImmersive()
                break
            }
        }
    }

    // 播放页返回语义（系统返回 + 顶部收起箭头一致）：
    //  - 沉浸歌词态：首次返回先唤出被隐藏的播放控件（退出沉浸），不退出播放页；
    //  - 控件已显示（非沉浸）：再返回才出栈，回到进入播放页之前的上级页面。
    // 必须 enabled = true 常驻：否则非沉浸态本 Handler 关闭时，后方 Tab（如搜索页
    // 的常驻 BackHandler）会抢走返回事件，导致无法出栈/返回错页面。
    BackHandler(enabled = true) {
        if (uiState.isLyricsImmersive) {
            viewModel.exitLyricsImmersive()
        } else {
            onBack()
        }
    }

    val settings by (settingsRepo?.settings
        ?: remember { MutableStateFlow(com.rc.harken.storage.settings.AppSettings()) })
        .collectAsStateWithLifecycle(initialValue = com.rc.harken.storage.settings.AppSettings())

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
        Box(modifier = Modifier.fillMaxSize()) {
            // 触摸屏障（垫在内容之下）：只接收「内容子节点未命中」的空白区事件，
            // 防止点击穿透到下层首页。
            //
            // 原 bug：屏障是内容的**父节点**，在 Main pass 里把所有未被消费的事件
            // 直接吃掉。进度条 Slider 拖拽在超过 touch slop 之前不会 consume，
            // 事件先被屏障吃掉 → Slider 永远进不了拖拽态，表现为「只能点击、拖不动」。
            // 改成**兄弟节点**后，命中测试优先落到上层的 Slider（内容），屏障不在
            // 它的命中路径里；只有空白区才由屏障兜底。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                // 任何触摸 / 滑动 / 点击都计入「用户操作」，重置无操作计时。
                                viewModel.markInteraction()
                                if (event.changes.none { it.isConsumed }) {
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                    },
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .displayCutoutPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 1. 顶栏：收起箭头 + 来源名 + 分享
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 收起：纯图标，去掉圆环 / 背景（仅保留中间图标）。
                    // 沉浸态首次点它先唤出播放控件（同系统返回语义）；控件已显示才真正返回。
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = {
                                    if (uiState.isLyricsImmersive) {
                                        viewModel.exitLyricsImmersive()
                                    } else {
                                        onBack()
                                    }
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = "收起",
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    // 顶栏中部：歌词模式显示「歌名 / 歌手 ›」（点击进百科），
                    // 否则显示播放来源名（官方歌词页顶栏对照）。
                    Box(
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .weight(1f),
                    ) {
                        if (uiState.showLyricsPanel && track != null) {
                            Column(
                                modifier = Modifier.clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                    onClick = { onOpenSongInfo(track.id) },
                                ),
                            ) {
                                Text(
                                    text = track.name,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White.copy(alpha = 0.9f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = track.artistNames.ifEmpty { "Harken" },
                                        fontSize = 11.sp,
                                        color = Color.White.copy(alpha = 0.6f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    Text(
                                        text = "  ›",
                                        fontSize = 11.sp,
                                        color = Color.White.copy(alpha = 0.6f),
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = state.context?.name
                                    ?: stringResource(R.string.player_playing),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.9f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (state.isTrial) {
                        Text(
                            text = stringResource(R.string.player_trial),
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    // 分享：纯图标，去掉圆环 / 背景（仅保留中间图标）。
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = { shareCurrentTrack() },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = stringResource(R.string.player_share),
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(20.dp),
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
                            onClick = {
                                // 沉浸态点空白处退出沉浸；否则在封面 ⇄ 歌词间切换。
                                if (uiState.isLyricsImmersive) {
                                    viewModel.exitLyricsImmersive()
                                } else {
                                    viewModel.toggleLyricsPanel()
                                }
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    // 唱片 ⇄ 歌词平滑过渡（替代原 if/else 硬切换）：旧内容微微放大
                    // 并淡出、新内容从 94% 缩放淡入，380ms 标准缓动——点击歌词不再生硬。
                    val centerMode = when {
                        uiState.showLyricsPanel -> CenterMode.Lyrics
                        settings.nowPlayingMode == NowPlayingMode.IMMERSIVE -> CenterMode.Cover
                        else -> CenterMode.Vinyl
                    }
                    AnimatedContent(
                        targetState = centerMode,
                        transitionSpec = {
                            (fadeIn(animationSpec = AppAnimations.smooth()) +
                                scaleIn(
                                    initialScale = 0.94f,
                                    animationSpec = AppAnimations.smooth(),
                                )) togetherWith
                                (fadeOut(animationSpec = AppAnimations.exit()) +
                                    scaleOut(
                                        targetScale = 1.05f,
                                        animationSpec = AppAnimations.exit(),
                                    ))
                        },
                        label = "nowPlayingCenter",
                    ) { mode ->
                        when (mode) {
                            CenterMode.Lyrics -> LyricsPanel(
                                lyrics = state.lyrics,
                                positionSeconds = state.positionMs / 1000.0,
                                showTranslation = settings.showTranslation,
                                verbatimLyrics = settings.verbatimLyrics,
                                isScrubbing = uiState.isLyricsScrubbing,
                                onSeekTo = { seconds -> controller.seekTo((seconds * 1000).toLong()) },
                                onScrubbingChange = viewModel::setLyricsScrubbing,
                                onSongInfo = { track?.id?.let { onOpenSongInfo(it) } },
                                modifier = Modifier.fillMaxSize(),
                            )
                            CenterMode.Cover -> ImmersiveCover(
                                coverURL = track?.album?.picUrl,
                                screenHeight = screenHeight,
                            )
                            CenterMode.Vinyl -> VinylRecordView(
                                coverURL = track?.album?.picUrl,
                                size = (screenHeight * 0.40f).coerceIn(220.dp, 340.dp),
                                isPlaying = state.isPlaying,
                            )
                        }
                    }
                }

                // 3-5. 社交行 / 控制条 / 五图标行：沉浸歌词模式下整体收起，
                // 中间歌词区（weight 1f）随之占满整屏；退出沉浸时平滑回归。
                // 收起 = 向下淡出收缩（出场 220ms）、回归 = 自底向上展开（进场 300ms）。
                AnimatedVisibility(
                    visible = !uiState.isLyricsImmersive,
                    enter = fadeIn(animationSpec = AppAnimations.enter()) +
                        expandVertically(
                            animationSpec = AppAnimations.enter(),
                            expandFrom = Alignment.Bottom,
                        ),
                    exit = fadeOut(animationSpec = AppAnimations.exit()) +
                        shrinkVertically(
                            animationSpec = AppAnimations.exit(),
                            shrinkTowards = Alignment.Bottom,
                        ),
                ) {
                Column {
                // 3. 社交行：曲名 + 歌手 + 红心 + 评论
                if (track != null) {
                    TrackSocialRow(
                        track = track,
                        isLiked = likedTrackIDs?.contains(track.id) == true,
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
                    onSongInfo = { track?.id?.let { onOpenSongInfo(it) } },
                    onDownload = { track?.let { showQualitySheet = true } },
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
                }
            }

            // 队列二级页面（从底部上拉；点击遮罩任意位置或下滑取消）
            // 官方做法：完整队列在原位展示（当前曲高亮），左滑出菜单点删、
            // 长按拖拽排序、随机模式下可「回到原始顺序」。
            if (uiState.showQueuePanel) {
                ModalBottomSheet(
                    onDismissRequest = viewModel::toggleQueuePanel,
                    containerColor = Color(0xFF1C1C1E).copy(alpha = 0.92f),
                    contentColor = Color.White,
                ) {
                    QueuePanel(
                        tracks = state.queue,
                        currentIndex = state.currentIndex,
                        onPlayAt = { index ->
                            controller.play(
                                state.queue,
                                index,
                                state.context,
                                playSourceOf(state.context),
                            )
                        },
                        onRemoveAt = { index -> controller.removeFromQueue(index) },
                        onMove = { from, to -> controller.moveInQueue(from, to) },
                        onClearQueue = { controller.clearQueue() },
                        onRestoreOrder = { controller.setShuffle(false) },
                        shuffleEnabled = state.shuffleEnabled,
                    )
                }
            }

            // 下载音质选择（弹出 BottomSheet，让用户改默认下载音质）。
            if (showQualitySheet) {
                ModalBottomSheet(
                    onDismissRequest = { showQualitySheet = false },
                    containerColor = Color(0xFF1C1C1E).copy(alpha = 0.92f),
                    contentColor = Color.White,
                ) {
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                        Text(
                            text = stringResource(R.string.player_download_quality),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        listOf(
                            AudioQuality.STANDARD to "标准",
                            AudioQuality.HIGHER to "较高",
                            AudioQuality.EXHIGH to "极高",
                            AudioQuality.LOSSLESS to "无损",
                            AudioQuality.HIRES to "Hi-Res",
                        ).forEach { (q, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showQualitySheet = false
                                        // fire-and-forget：下载跑在 repo 进程级 scope，
                                        // 离开播放页也不会中断；toast 由上方 bus 订阅统一处理。
                                        track?.let { t -> downloadRepo?.download(t, q.apiLevel) }
                                    }
                                    .padding(vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(text = label, fontSize = 15.sp, color = Color.White)
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
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
            // 关注按钮已移除：无法像官方客户端那样真实关注，不做只改本地状态的壳。
            Text(
                text = track.artistNames.ifEmpty { "Harken" },
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.62f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
/**
 * 沉浸模式大封面（播放页样式 `IMMERSIVE`）：居中大图、圆角，无黑胶唱片与旋转。
 * 与 VINYL 的 [VinylRecordView] 二选一，由设置页「播放页样式」驱动。
 */
@Composable
private fun ImmersiveCover(
    coverURL: String?,
    screenHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val size = (screenHeight * 0.54f).coerceIn(280.dp, 440.dp)
    AsyncImage(
        model = Formatters.resizedImageURL(coverURL, 640),
        contentDescription = stringResource(R.string.player_cover),
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(24.dp)),
    )
}

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

/** 由 `PlayContext.Kind` 反推 `PlaySource`（队列重放时保持来源一致）。 */
private fun playSourceOf(context: com.rc.harken.playback.PlayContext?):
    com.rc.harken.playback.PlaySource = when (context?.kind) {
    com.rc.harken.playback.PlayContext.Kind.ALBUM -> com.rc.harken.playback.PlaySource.ALBUM
    com.rc.harken.playback.PlayContext.Kind.ARTIST -> com.rc.harken.playback.PlaySource.ARTIST
    com.rc.harken.playback.PlayContext.Kind.DAILY -> com.rc.harken.playback.PlaySource.DAILY
    com.rc.harken.playback.PlayContext.Kind.CLOUD -> com.rc.harken.playback.PlaySource.CLOUD
    com.rc.harken.playback.PlayContext.Kind.PLAYLIST,
    com.rc.harken.playback.PlayContext.Kind.HEARTBEAT,
    -> com.rc.harken.playback.PlaySource.PLAYLIST
    else -> com.rc.harken.playback.PlaySource.NONE
}
