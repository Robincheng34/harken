// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 搜索结果分区呈现（P1-1 `components/SearchResultSections.kt`）。
//
// 1:1 复刻 iOS `SearchView.tabContent`：
//  - `ALL`：单曲（取前 6 首）+ 歌手/专辑/歌单各取前 8 的横滑 shelf，每段带
//    「查看全部」→ 切到对应 Tab；
//  - `SONGS`：单曲全量列表；`ARTISTS`：圆形头像双列网格；`ALBUMS`/`PLAYLISTS`：
//    方形封面双列网格；
//  - 各 Tab 内容为空且非加载中 → 空态「没有找到相关结果」。
//
// 复用 `:design-system` 的 `TrackListView` / `PlaylistShelf` / `CoverCard` /
// `SectionHeader` / `ErrorStateView`。

package com.rc.harken.feature.search.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed as lazyItemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.rc.harken.design.component.CoverCard
import com.rc.harken.design.component.PlaylistShelf
import com.rc.harken.design.component.SectionHeader
import com.rc.harken.design.component.TrackListView
import com.rc.harken.design.component.TrackRow
import com.rc.harken.design.theme.Layout
import com.rc.harken.feature.search.R
import com.rc.harken.feature.search.SearchTab
import com.rc.harken.feature.search.SearchUiState
import com.rc.harken.net.model.AlbumSummary
import com.rc.harken.net.model.ArtistSummary
import com.rc.harken.net.model.PlaylistSummary
import com.rc.harken.net.model.Track

/**
 * 搜索结果分区内容（按当前 [SearchUiState.tab] 分发）。
 *
 * @param currentTrackId 当前播放曲（单曲列表高亮）。
 * @param onPlayTrack 点击单曲。
 * @param onOpenArtist / onOpenAlbum / onOpenPlaylist 三类详情跳转。
 * @param onSeeAllAll 分区「查看全部」→ 切 Tab。
 * @param bottomClearance 列表尾部净空（迷你播放条 + TabBar 悬浮 chrome 占位）。
 */
@Composable
fun SearchResultSections(
    uiState: SearchUiState,
    currentTrackId: Long?,
    onPlayTrack: (Track, Int) -> Unit,
    onOpenArtist: (Long) -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onSelectTab: (SearchTab) -> Unit,
    modifier: Modifier = Modifier,
    bottomClearance: Dp = 0.dp,
) {
    val contentModifier = modifier.padding(bottom = bottomClearance)
    when (uiState.tab) {
        SearchTab.ALL -> AllTab(
            uiState = uiState,
            currentTrackId = currentTrackId,
            onPlayTrack = onPlayTrack,
            onOpenArtist = onOpenArtist,
            onOpenAlbum = onOpenAlbum,
            onOpenPlaylist = onOpenPlaylist,
            onSelectTab = onSelectTab,
            modifier = contentModifier,
        )

        SearchTab.SONGS -> TrackListView(
            tracks = uiState.songs,
            currentTrackId = currentTrackId,
            onPlay = onPlayTrack,
            modifier = contentModifier.fillMaxWidth(),
        )

        SearchTab.ARTISTS -> ArtistGrid(
            artists = uiState.artists,
            onOpenArtist = onOpenArtist,
            modifier = contentModifier,
        )

        SearchTab.ALBUMS -> AlbumGrid(
            albums = uiState.albums,
            onOpenAlbum = onOpenAlbum,
            modifier = contentModifier,
        )

        SearchTab.PLAYLISTS -> PlaylistGrid(
            playlists = uiState.playlists,
            onOpenPlaylist = onOpenPlaylist,
            modifier = contentModifier,
        )
    }
}

// MARK: - 综合 Tab

/** `ALL`：四个分区纵向堆叠，每段前若干项 + 「查看全部」。 */
@Composable
private fun AllTab(
    uiState: SearchUiState,
    currentTrackId: Long?,
    onPlayTrack: (Track, Int) -> Unit,
    onOpenArtist: (Long) -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onSelectTab: (SearchTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 原 bug：本分区是 Column(fillMaxSize)，第一个子项 TrackListView 内含
    // LazyColumn 吃掉全部高度，后面的歌手/专辑/歌单 shelf 高度为 0 且不可达。
    // 改为单一 LazyColumn，各分区作为 item；单曲行直接 items 化（不再嵌套
    // 内含 LazyColumn 的 TrackListView），各分区内容均可见可滚。
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        if (uiState.songs.isNotEmpty()) {
            // iOS: SectionHeader(title:"单曲") { model.tab = .songs }
            item(key = "songs_header") {
                SectionHeader(
                    title = stringResource(R.string.search_section_songs),
                    actionLabel = stringResource(R.string.search_see_all),
                    onAction = { onSelectTab(SearchTab.SONGS) },
                )
            }
            lazyItemsIndexed(
                uiState.songs.take(ALL_SONGS_LIMIT),
                key = { index, track -> "song-${track.id}-$index" },
            ) { index, track ->
                TrackRow(
                    track = track,
                    index = index + 1,
                    isCurrent = track.id == currentTrackId,
                    onPlay = { onPlayTrack(track, index) },
                )
            }
        }
        if (uiState.artists.isNotEmpty()) {
            item(key = "artists") {
                Column {
                    Shelf(
                        title = stringResource(R.string.search_section_artists),
                        onSeeAll = { onSelectTab(SearchTab.ARTISTS) },
                    ) {
                        ArtistRow(
                            artists = uiState.artists.take(ALL_CARD_LIMIT),
                            onOpenArtist = onOpenArtist,
                        )
                    }
                }
            }
        }
        if (uiState.albums.isNotEmpty()) {
            item(key = "albums") {
                Column {
                    Shelf(
                        title = stringResource(R.string.search_section_albums),
                        onSeeAll = { onSelectTab(SearchTab.ALBUMS) },
                    ) {
                        AlbumRow(
                            albums = uiState.albums.take(ALL_CARD_LIMIT),
                            onOpenAlbum = onOpenAlbum,
                        )
                    }
                }
            }
        }
        if (uiState.playlists.isNotEmpty()) {
            item(key = "playlists") {
                Column {
                    Shelf(
                        title = stringResource(R.string.search_section_playlists),
                        onSeeAll = { onSelectTab(SearchTab.PLAYLISTS) },
                    ) {
                        PlaylistRow(
                            playlists = uiState.playlists.take(ALL_CARD_LIMIT),
                            onOpenPlaylist = onOpenPlaylist,
                        )
                    }
                }
            }
        }
    }
}

/** iOS `Shelf(title:seeAll:content:)`：带「查看全部」的分区外壳。 */
@Composable
private fun Shelf(
    title: String,
    onSeeAll: () -> Unit,
    content: @Composable () -> Unit,
) {
    SectionHeader(
        title = title,
        actionLabel = stringResource(R.string.search_see_all),
        onAction = onSeeAll,
    )
    content()
}

// MARK: - 网格

/** 歌手双列网格（圆形头像，iOS `artistCards`：128dp 圆形 + 13sp 名字 + 140dp 卡宽）。 */
@Composable
private fun ArtistGrid(
    artists: List<ArtistSummary>,
    onOpenArtist: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = Layout.contentInset,
            end = Layout.contentInset,
            top = 8.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        itemsIndexed(artists, key = { _, item -> item.id }) { _, artist ->
            ArtistCard(artist = artist, onOpen = { onOpenArtist(artist.id) })
        }
    }
}

/** 专辑双列网格（方形封面 + 名称 + 歌手）。 */
@Composable
private fun AlbumGrid(
    albums: List<AlbumSummary>,
    onOpenAlbum: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = Layout.contentInset,
            end = Layout.contentInset,
            top = 8.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        itemsIndexed(albums, key = { _, item -> item.id }) { _, album ->
            CoverCard(
                imageUrl = com.rc.harken.net.model.Formatters.resizedImageURL(album.coverUrl, 384),
                contentDescription = album.name,
                title = album.name,
                subtitle = album.artistName,
                onClick = { onOpenAlbum(album.id) },
            )
        }
    }
}

/** 歌单双列网格（方形封面 + 名称 + 播放量角标）。 */
@Composable
private fun PlaylistGrid(
    playlists: List<PlaylistSummary>,
    onOpenPlaylist: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = Layout.contentInset,
            end = Layout.contentInset,
            top = 8.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        itemsIndexed(playlists, key = { _, item -> item.id }) { _, playlist ->
            CoverCard(
                imageUrl = com.rc.harken.net.model.Formatters.resizedImageURL(playlist.coverURL, 384),
                contentDescription = playlist.name,
                title = playlist.name,
                playCount = playlist.playCount,
                onClick = { onOpenPlaylist(playlist.id) },
            )
        }
    }
}

// MARK: - 综合 Tab 的横滑行

/** 歌手横滑（圆形头像）。 */
@Composable
private fun ArtistRow(
    artists: List<ArtistSummary>,
    onOpenArtist: (Long) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        lazyItemsIndexed(artists, key = { _, item -> item.id }) { _, artist ->
            ArtistCard(artist = artist, onOpen = { onOpenArtist(artist.id) })
        }
    }
}

/** 专辑横滑（方形封面）。 */
@Composable
private fun AlbumRow(
    albums: List<AlbumSummary>,
    onOpenAlbum: (Long) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        lazyItemsIndexed(albums, key = { _, item -> item.id }) { _, album ->
            CoverCard(
                imageUrl = com.rc.harken.net.model.Formatters.resizedImageURL(album.coverUrl, 384),
                contentDescription = album.name,
                title = album.name,
                subtitle = album.artistName,
                size = 128.dp,
                onClick = { onOpenAlbum(album.id) },
            )
        }
    }
}

/** 歌单横滑（方形封面 + 播放量）。与其余分区一致开启官方逐个入场动效。 */
@Composable
private fun PlaylistRow(
    playlists: List<PlaylistSummary>,
    onOpenPlaylist: (Long) -> Unit,
) {
    PlaylistShelf(
        title = "",
        items = playlists,
        onOpen = onOpenPlaylist,
        staggered = true,
    )
}

// MARK: - 卡片

/** 歌手卡（iOS `artistCards`：128dp 圆形封面 + 13sp 名字）。 */
@Composable
private fun ArtistCard(
    artist: ArtistSummary,
    onOpen: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clickable(onClick = onOpen),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AsyncImage(
            model = com.rc.harken.net.model.Formatters.resizedImageURL(artist.picUrl, 256),
            contentDescription = artist.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(128.dp)
                .clip(CircleShape),
        )
        Text(
            text = artist.name,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

/** iOS `ALL` 分区单品预览条数。 */
private const val ALL_SONGS_LIMIT = 6

/** iOS `ALL` 分区卡片预览条数。 */
private const val ALL_CARD_LIMIT = 8