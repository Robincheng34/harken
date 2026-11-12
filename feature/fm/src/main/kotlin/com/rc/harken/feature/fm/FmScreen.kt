// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 漫游页（T23 `FmScreen.kt`，iOS `FMView` 1:1 移植）。
//
// 布局：FmBackground（模糊封面）+ 居中封面（768px / 16dp 圆角 / 播放时 scale 1.0、
// 暂停 0.94，bouncy 动画）+ 曲名（VIP 角标）+ 歌手 + 控制条；未登录展示登录引导。

package com.rc.harken.feature.fm

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.rc.harken.design.composition.LocalIsLoggedIn
import com.rc.harken.design.composition.LocalMusicController
import com.rc.harken.design.composition.LocalNeteaseApi
import com.rc.harken.design.composition.LocalToastState
import com.rc.harken.design.theme.AppAnimations
import com.rc.harken.design.theme.BrandRed
import com.rc.harken.feature.fm.components.FmBackground
import com.rc.harken.feature.fm.components.FmControls
import com.rc.harken.net.model.Formatters

/** 漫游页 Tab 起始路由（与 `:app` `Routes.FM` 的字符串契约）。 */

/**
 * 私人漫游页。
 *
 * @param onOpenLogin 未登录时点击「登录」的回调。
 */
@Composable
fun FmScreen(
    onOpenLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val controller = LocalMusicController.current
        ?: error("LocalMusicController 必须在 HarkenApp 提供")
    val api = LocalNeteaseApi.current
        ?: error("LocalNeteaseApi 必须在 HarkenApp 提供")
    val loggedInFlow = LocalIsLoggedIn.current
    val isLoggedIn by loggedInFlow.collectAsStateWithLifecycle()
    val toast = LocalToastState.current
    // 文案在组合期取出，供 LaunchedEffect（非组合上下文）使用。
    val likeFailedMessage = stringResource(R.string.fm_like_failed)

    val viewModel = remember(controller) {
        FmViewModel(
            controller = MusicControllerFmAdapter(controller),
            isLoggedIn = { isLoggedIn },
            // 收藏（红心）走真实接口，ViewModel 内做乐观更新 + 失败回滚。
            api = api,
        )
    }
    // 页面销毁时取消 ViewModel 内部协程（原 bug：自建 scope 永不取消，状态收集器常驻泄漏）。
    DisposableEffect(Unit) { onDispose { viewModel.cancel() } }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 收藏失败必须有可见反馈（原 bug：`runCatching` 吞掉异常，点了没反应也没提示）。
    LaunchedEffect(viewModel, likeFailedMessage) {
        viewModel.likeFailures.collect { toast.show(likeFailedMessage) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        FmBackground(coverURL = uiState.currentTrack?.album?.picUrl)

        if (uiState.showLoginPrompt) {
            FmLoginPrompt(onOpenLogin = onOpenLogin)
            return@Box
        }

        FmContent(
            uiState = uiState,
            onTogglePlay = viewModel::togglePlay,
            onStart = viewModel::start,
            onNext = viewModel::next,
            onTrash = viewModel::trash,
            onToggleLike = viewModel::toggleLike,
        )
    }
}

/** 沉浸内容：封面 + 文案 + 控制条（iOS `FMView.content`）。 */
@Composable
private fun FmContent(
    uiState: FmUiState,
    onTogglePlay: () -> Unit,
    onStart: () -> Unit,
    onNext: () -> Unit,
    onTrash: () -> Unit,
    onToggleLike: (Long) -> Unit,
) {
    val track = uiState.currentTrack
    // iOS: .scaleEffect(isPlaying && isFMMode ? 1 : 0.94) + .animation(bouncy)
    val playing = uiState.isPlaying && uiState.isFmMode
    val coverScale by animateFloatAsState(
        targetValue = if (playing) 1f else 0.94f,
        animationSpec = AppAnimations.bouncy(),
        label = "fmCoverScale",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(modifier = Modifier.height(40.dp))

        // 封面（768px，iOS cornerRadius 16）
        Box(
            modifier = Modifier
                .size(240.dp)
                .scale(coverScale)
                .shadow(14.dp, MaterialTheme.shapes.large, ambientColor = androidx.compose.ui.graphics.Color.Black)
                .clip(MaterialTheme.shapes.large),
            contentAlignment = Alignment.Center,
        ) {
            if (track?.album?.picUrl != null) {
                AsyncImage(
                    model = Formatters.resizedImageURL(track.album.picUrl, 768),
                    contentDescription = track.album.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(60.dp),
                )
            }
            if (uiState.isBuffering) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 曲名 + VIP 角标（iOS `fee == 1`）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = track?.name ?: stringResource(R.string.fm_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (track?.fee == 1) {
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = "VIP",
                    style = MaterialTheme.typography.labelSmall,
                    color = BrandRed,
                )
            }
        }
        Text(
            text = track?.artistNames ?: stringResource(R.string.fm_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(modifier = Modifier.height(28.dp))

        if (uiState.isFmMode) {
            FmControls(
                isPlaying = uiState.isPlaying,
                // 原 bug：这里硬编码 false → 红心永远空心，点了看不到任何变化。
                isLiked = uiState.isCurrentTrackLiked,
                onTrash = onTrash,
                onTogglePlay = onTogglePlay,
                onNext = onNext,
                onToggleLike = { track?.let { onToggleLike(it.id) } },
            )
        } else {
            // iOS: Label("开始漫游") + Capsule + accentGradient
            Button(
                onClick = onStart,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = BrandRed),
            ) {
                Icon(imageVector = Icons.Filled.GraphicEq, contentDescription = null)
                Spacer(modifier = Modifier.size(6.dp))
                Text(text = stringResource(R.string.fm_start))
            }
        }

        Spacer(modifier = Modifier.height(80.dp))
    }
}

/** 未登录引导（iOS `FMView.loginPrompt`）。 */
@Composable
private fun FmLoginPrompt(onOpenLogin: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.GraphicEq,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(52.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "登录后开启私人漫游",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "网易云会根据你的听歌口味推荐音乐",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 280.dp),
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(onClick = onOpenLogin, colors = ButtonDefaults.buttonColors(containerColor = BrandRed)) {
            Text(text = "登录")
        }
    }
}
