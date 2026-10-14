// Ported from kumone by missuo (LGPL-3.0)
//
// 搜索页 UI 状态（T20 `SearchUiState.kt`，iOS `SearchViewModel` 1:1 移植）。
//
// iOS 以 `Tab`（综合/单曲/歌手/专辑/歌单）驱动结果分区，并用 `loadedTabs` 做
// 「按需惰性加载」：每个 Tab 首次展示时才发对应类型的请求，已加载的不重复请求。
// 安卓侧保留同一语义（见 `SearchViewModel.load(tab)`）。

package im.missuo.kumone.feature.search

import im.missuo.kumone.net.model.AlbumSummary
import im.missuo.kumone.net.model.ArtistSummary
import im.missuo.kumone.net.model.PlaylistSummary
import im.missuo.kumone.net.model.Track

/**
 * 结果分区 Tab（iOS `SearchViewModel.Tab`）。
 *
 * @param searchType 对应 `SearchType.value`；`ALL` 为聚合维度（并发四路）。
 */
enum class SearchTab(val searchType: Int?) {
    ALL(null),
    SONGS(im.missuo.kumone.net.model.SearchType.SONGS.value),
    ARTISTS(im.missuo.kumone.net.model.SearchType.ARTISTS.value),
    ALBUMS(im.missuo.kumone.net.model.SearchType.ALBUMS.value),
    PLAYLISTS(im.missuo.kumone.net.model.SearchType.PLAYLISTS.value),
}

/** 搜索页聚合状态。 */
data class SearchUiState(
    val query: String = "",
    val tab: SearchTab = SearchTab.ALL,
    /** 输入过程中的联想建议（iOS `searchSuggest`，Android 增强：iOS 用 `.searchable` 系统栏）。 */
    val suggestions: im.missuo.kumone.net.model.SearchSuggestBody? = null,
    val songs: List<Track> = emptyList(),
    val artists: List<ArtistSummary> = emptyList(),
    val albums: List<AlbumSummary> = emptyList(),
    val playlists: List<PlaylistSummary> = emptyList(),
    val isSearching: Boolean = false,
    /** 已发起过请求的 Tab（iOS `loadedTabs`，避免重复加载）。 */
    val loadedTabs: Set<SearchTab> = emptySet(),
    val error: String? = null,
) {
    /** 当前 Tab 下是否「空」（iOS `currentEmpty`）。 */
    fun currentEmpty(): Boolean = when (tab) {
        SearchTab.ALL -> songs.isEmpty() && artists.isEmpty()
        SearchTab.SONGS -> songs.isEmpty()
        SearchTab.ARTISTS -> artists.isEmpty()
        SearchTab.ALBUMS -> albums.isEmpty()
        SearchTab.PLAYLISTS -> playlists.isEmpty()
    }

    /** 查询为空时展示引导页而非分段控件（iOS `searchText.isEmpty`）。 */
    val showSegmented: Boolean get() = query.isNotBlank()
}