// Ported from kumone by missuo (LGPL-3.0)
//
// 我的页（T24 `LibraryScreen.kt`，iOS `SidebarView` 手机版 1:1 移植）。
//
// 登录门控同 iOS：未登录展示登录引导 + 「已下载」；登录后展示个人行 + 创建/收藏歌单。

package im.missuo.kumone.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.missuo.kumone.design.composition.LocalIsLoggedIn
import im.missuo.kumone.design.composition.LocalNeteaseApi
import im.missuo.kumone.design.navigation.Destinations
import im.missuo.kumone.design.theme.BrandRed
import im.missuo.kumone.design.theme.Layout
import im.missuo.kumone.feature.library.components.LibraryIcons
import im.missuo.kumone.feature.library.components.LibraryPlaylistRow
import im.missuo.kumone.feature.library.components.LibraryRow
import im.missuo.kumone.feature.library.components.LibrarySectionHeader
import im.missuo.kumone.net.model.Formatters

/** 我的页 Tab 起始路由（与 `:app` `Routes.LIBRARY` 的字符串契约）。 */
const val LIBRARY_ROUTE = "library"

/**
 * 我的页。
 *
 * @param onNavigate 跳转覆盖层路由（每日推荐 / 最近播放 / 收藏 / 云盘 / 设置）。
 * @param onOpenLogin 未登录时点击登录。
 */
@Composable
fun LibraryScreen(
    onNavigate: (String) -> Unit,
    onOpenLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val api = LocalNeteaseApi.current ?: error("LocalNeteaseApi 必须在 KumoneApp 提供")
    val loggedInFlow = LocalIsLoggedIn.current
    val isLoggedIn by loggedInFlow.collectAsStateWithLifecycle()

    val viewModel = remember(api) { LibraryViewModel(api, isLoggedIn = { isLoggedIn }) }
    // 页面销毁时取消 ViewModel 内部协程（原 bug：自建 scope 永不取消，收集器常驻泄漏）。
    DisposableEffect(Unit) { onDispose { viewModel.cancel() } }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(isLoggedIn) { viewModel.load() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 24.dp),
    ) {
        Text(
            text = if (uiState.isLoggedIn && uiState.nickname.isNotEmpty()) {
                uiState.nickname
            } else {
                stringResource(R.string.library_title)
            },
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )

        if (!uiState.isLoggedIn) {
            // iOS 未登录：仅「已下载」+ 登录引导。
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Spacer(modifier = Modifier.height(40.dp))
                Text(
                    text = stringResource(R.string.library_login_prompt),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onOpenLogin,
                    colors = ButtonDefaults.buttonColors(containerColor = BrandRed),
                ) {
                    Text(text = stringResource(R.string.library_login))
                }
            }
            LibraryRow(
                icon = LibraryIcons.Downloaded,
                title = "已下载",
            )
        } else {
            LibraryRow(
                icon = LibraryIcons.LikedSongs,
                title = stringResource(R.string.library_liked_songs),
                // 跳转「我的收藏」歌单页（FavoritesScreen）。此前误用 Destinations.LIBRARY
                // —— overlay 导航图中不存在该路由，点击必崩（IllegalArgumentException）。
                onClick = { onNavigate(Destinations.FAVORITES) },
            )
            LibraryRow(
                icon = LibraryIcons.Downloaded,
                title = "已下载",
            )
            LibraryRow(
                icon = LibraryIcons.Daily,
                title = stringResource(R.string.library_daily_recommend),
                onClick = { onNavigate(Destinations.DAILY_SONGS) },
            )
            LibraryRow(
                icon = LibraryIcons.Recents,
                title = stringResource(R.string.library_recent_plays),
                onClick = { onNavigate(Destinations.RECENT_PLAYS) },
            )
            LibraryRow(
                icon = LibraryIcons.Favorites,
                title = stringResource(R.string.library_favorites),
                onClick = { onNavigate(Destinations.FAVORITES) },
            )
            LibraryRow(
                icon = LibraryIcons.Cloud,
                title = stringResource(R.string.library_cloud),
                onClick = { onNavigate(Destinations.CLOUD) },
            )

            if (uiState.createdPlaylists.isNotEmpty()) {
                LibrarySectionHeader(title = stringResource(R.string.library_my_playlists))
                uiState.createdPlaylists.forEach { playlist ->
                    LibraryPlaylistRow(
                        title = playlist.name,
                        coverURL = Formatters.resizedImageURL(playlist.coverURL, 160),
                        subtitle = "${playlist.trackCount} 首",
                        onClick = { onNavigate(Destinations.playlistDetail(playlist.id)) },
                    )
                }
            }

            if (uiState.subscribedPlaylists.isNotEmpty()) {
                LibrarySectionHeader(title = "收藏的歌单")
                uiState.subscribedPlaylists.forEach { playlist ->
                    LibraryPlaylistRow(
                        title = playlist.name,
                        coverURL = Formatters.resizedImageURL(playlist.coverURL, 160),
                        subtitle = "${playlist.trackCount} 首",
                        onClick = { onNavigate(Destinations.playlistDetail(playlist.id)) },
                    )
                }
            }
        }

        LibraryRow(
            icon = LibraryIcons.Settings,
            title = stringResource(R.string.library_settings),
            onClick = { onNavigate(Destinations.SETTINGS) },
        )

        Spacer(modifier = Modifier.height(Layout.fullChromeClearance))
    }
}
