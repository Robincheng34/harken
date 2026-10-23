// Ported from kumone by missuo (LGPL-3.0)
//
// 歌曲信息页（播放页「歌曲信息」入口的全屏覆盖层）。
//
// 数据全部来自真实接口，无任何编造字段：
//  - 歌曲基础信息（曲名/歌手/专辑/时长）：`songDetails`
//  - 专辑详情（封面/发行日期/厂牌/曲目数/简介）：`album`
//  - 艺人（头像/简介/作品数/关注态）：`artist`
//  - 艺人其他专辑（横滑卡片）：`artistAlbums`
//
// 官方「歌曲百科」里的 曲风/语种/BPM/制作/乐谱/艺人奖项 等字段在逆向产物中
// 没有对应接口、本项目数据层也无这些字段，故不做——宁缺毋滥，不编造、不占位。

package im.missuo.kumone.feature.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import im.missuo.kumone.design.component.ErrorStateView
import im.missuo.kumone.design.composition.LocalIsLoggedIn
import im.missuo.kumone.design.composition.LocalNeteaseApi
import im.missuo.kumone.design.composition.LocalToastState
import im.missuo.kumone.design.theme.GlassTopAppBar
import im.missuo.kumone.net.model.AlbumDetail
import im.missuo.kumone.net.model.AlbumSummary
import im.missuo.kumone.net.model.ArtistSummary
import im.missuo.kumone.net.model.Formatters
import im.missuo.kumone.net.model.Track
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 歌曲信息页的一次性加载态。 */
private data class SongInfoState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val song: Track? = null,
    val album: AlbumDetail? = null,
    val artist: ArtistSummary? = null,
    val albums: List<AlbumSummary> = emptyList(),
    val followed: Boolean = false,
)

/**
 * 歌曲信息页。
 *
 * @param trackId 当前曲 id（来自路由参数）。
 * @param onOpenAlbum 点击专辑卡片 / 艺人其他专辑时跳转专辑详情。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongInfoScreen(
    trackId: Long,
    onBack: () -> Unit,
    onOpenAlbum: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val api = LocalNeteaseApi.current ?: error("LocalNeteaseApi 必须在 KumoneApp 提供")
    val toast = LocalToastState.current
    val isLoggedIn by LocalIsLoggedIn.current.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // 系统返回手势（与专辑/歌手/歌单详情页同款）：必须显式接管——否则下方 Tab 页
    // （如搜索页）的常驻 BackHandler 会抢走返回事件，导致百科页返回手势失效。
    BackHandler(enabled = true) { onBack() }

    val followFailedMsg = stringResource(R.string.song_info_follow_failed)
    val loginRequiredMsg = stringResource(R.string.player_login_required)
    val loadFailedMsg = stringResource(R.string.song_info_load_failed)

    var state by remember { mutableStateOf(SongInfoState()) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(trackId, reloadKey) {
        state = state.copy(loading = true, failed = false)
        val song = runCatching { api.songDetails(listOf(trackId)) }.getOrNull()?.songs?.firstOrNull()
        if (song == null) {
            state = SongInfoState(loading = false, failed = true)
            return@LaunchedEffect
        }
        val album = song.album.id.takeIf { it > 0 }
            ?.let { runCatching { api.album(it) }.getOrNull()?.album }
        val artistId = song.artists.firstOrNull()?.id ?: 0L
        val artistResp = if (artistId > 0) runCatching { api.artist(artistId) }.getOrNull() else null
        val albums = if (artistId > 0) {
            runCatching { api.artistAlbums(artistId) }.getOrNull().orEmpty()
        } else {
            emptyList()
        }
        state = SongInfoState(
            loading = false,
            song = song,
            album = album,
            artist = artistResp?.artist,
            albums = albums,
            followed = artistResp?.artist?.followed ?: false,
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            GlassTopAppBar(
                title = { Text(text = stringResource(R.string.player_info_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
            )
        },
    ) { padding ->
        val current = state
        when {
            current.loading -> Box(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }

            current.failed -> ErrorStateView(
                message = loadFailedMsg,
                onRetry = { reloadKey++ },
                modifier = Modifier.padding(padding).fillMaxSize(),
            )

            else -> SongInfoContent(
                state = current,
                isLoggedIn = isLoggedIn,
                onFollow = { target ->
                    val artistId = current.artist?.id ?: 0L
                    scope.launch {
                        val ok = runCatching { api.subscribeArtist(artistId, target) }.isSuccess
                        if (ok) {
                            state = state.copy(followed = target)
                        } else {
                            toast.show(followFailedMsg)
                        }
                    }
                },
                onRequireLogin = { toast.show(loginRequiredMsg) },
                onOpenAlbum = onOpenAlbum,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun SongInfoContent(
    state: SongInfoState,
    isLoggedIn: Boolean,
    onFollow: (Boolean) -> Unit,
    onRequireLogin: () -> Unit,
    onOpenAlbum: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val song = state.song ?: return
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(modifier = Modifier.height(4.dp))

        // 1. 歌曲基础信息
        SectionCard {
            Text(
                text = song.name,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (song.artistNames.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = song.artistNames,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            InfoLine(stringResource(R.string.player_info_album), song.album.name)
            InfoLine(
                stringResource(R.string.player_info_duration),
                Formatters.duration(song.durationSeconds),
            )
            InfoLine(stringResource(R.string.player_info_id), song.id.toString())
        }

        // 2. 专辑卡片（真实字段：封面/发行/厂牌/曲目数/简介）
        val album = state.album
        if (album != null) {
            SectionTitle(stringResource(R.string.song_info_album_section))
            SectionCard(modifier = Modifier.clickable { onOpenAlbum(album.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = Formatters.resizedImageURL(album.picUrl, 300),
                        contentDescription = album.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(88.dp)
                            .clip(MaterialTheme.shapes.medium),
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = album.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        album.artist?.name?.let { artistName ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = artistName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                val release = formatPublishDate(album.publishTime)
                if (release.isNotEmpty()) {
                    InfoLine(stringResource(R.string.song_info_release), release)
                }
                album.company?.takeIf { it.isNotEmpty() }?.let {
                    InfoLine(stringResource(R.string.song_info_company), it)
                }
                if (album.size > 0) {
                    InfoLine(stringResource(R.string.song_info_track_count), album.size.toString())
                }
                album.description?.takeIf { it.isNotEmpty() }?.let { desc ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // 3. 艺人卡片（真实字段：头像/简介/作品数/关注）
        val artist = state.artist
        if (artist != null) {
            SectionTitle(stringResource(R.string.player_info_artist))
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = Formatters.resizedImageURL(artist.picUrl, 200),
                        contentDescription = artist.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape),
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = artist.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        artist.alias.firstOrNull()?.takeIf { it.isNotEmpty() }?.let { alias ->
                            Text(
                                text = alias,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(
                                R.string.song_info_works,
                                artist.albumSize,
                                artist.musicSize,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    OutlinedButton(
                        onClick = { if (isLoggedIn) onFollow(!state.followed) else onRequireLogin() },
                    ) {
                        Text(
                            text = stringResource(
                                if (state.followed) R.string.player_following else R.string.player_follow,
                            ),
                        )
                    }
                }
                artist.briefDesc?.takeIf { it.isNotEmpty() }?.let { bio ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.song_info_bio),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = bio,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        // 4. 艺人其他专辑（横滑卡片，点击跳专辑详情）
        if (state.albums.isNotEmpty()) {
            SectionTitle(stringResource(R.string.song_info_albums_section))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(state.albums, key = { it.id }) { item ->
                    Column(
                        modifier = Modifier
                            .width(112.dp)
                            .clickable { onOpenAlbum(item.id) },
                    ) {
                        AsyncImage(
                            model = Formatters.resizedImageURL(item.coverUrl, 300),
                            contentDescription = item.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(112.dp)
                                .clip(MaterialTheme.shapes.medium),
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val year = item.publishYear
                        if (year.isNotEmpty()) {
                            Text(
                                text = year,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 分区标题。 */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 信息行（label : value）。 */
@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 内容卡片（surfaceVariant 圆角底）。 */
@Composable
private fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

/** 发行时间戳 → `yyyy-MM-dd`（0 或异常回落空串）。 */
private fun formatPublishDate(ms: Long): String {
    if (ms <= 0) return ""
    return runCatching {
        Instant.ofEpochMilli(ms)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
    }.getOrDefault("")
}
