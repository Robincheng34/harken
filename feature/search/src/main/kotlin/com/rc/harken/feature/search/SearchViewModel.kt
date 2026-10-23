// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 搜索页 ViewModel（T20 `SearchViewModel.kt`，iOS `SearchViewModel` 1:1 移植）。
//
// 惰性按 Tab 加载（iOS `load(tab:)`）：每个 Tab 首次展示时并发/单独发起对应类型的
// 检索；`loadedTabs` 记录已加载的 Tab，不重复请求；查询词变化时清空结果与已加载
// 集合（iOS `setQuery`）。输入过程另发联想建议（`searchSuggest`）。
// 纯 Kotlin + 协程，可纯 JVM 单测（见 `SearchViewModelTest`）。

package com.rc.harken.feature.search

import com.rc.harken.net.api.NeteaseApi
import com.rc.harken.net.model.AlbumSummary
import com.rc.harken.net.model.ArtistSummary
import com.rc.harken.net.model.PlaylistSummary
import com.rc.harken.net.model.SearchSuggestBody
import com.rc.harken.net.model.Track
import com.rc.harken.storage.search.SearchHistoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SearchViewModel(
    private val api: NeteaseApi,
    private val history: SearchHistoryRepository? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val _uiState: MutableStateFlow<SearchUiState> = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        // 搜索历史：仓库冷 Flow → UI 状态（官方落地页「搜索历史」）。
        history?.let { repo ->
            scope.launch {
                repo.history.collect { list ->
                    _uiState.update { it.copy(history = list) }
                }
            }
        }
    }

    /** 热搜榜（官方落地页）：拉一次，失败静默（落地页降级为仅历史）。 */
    fun loadHotSearches() {
        if (_uiState.value.hotSearches.isNotEmpty() || _uiState.value.isLoadingHot) return
        scope.launch {
            _uiState.update { it.copy(isLoadingHot = true) }
            // 过滤两类不展示的条目：
            //  1) 官方推广位（alg == "featured"，热搜榜第 3 条那种广告）——用户要求移除；
            //  2) searchWord 空白条目（防御，正常不出现）。
            // 过滤后 UI 按 index+1 重排名次，后续条目自动前移补位。
            val hot = runCatching { api.hotSearchDetail() }.getOrNull().orEmpty()
                .filter { it.searchWord.isNotBlank() && it.alg != "featured" }
            _uiState.update { it.copy(hotSearches = hot, isLoadingHot = false) }
        }
    }

    /** 输入变化：更新关键字、清空旧结果与已加载集合，并触发联想（iOS `setQuery`）。 */
    fun setQuery(query: String) {
        val previous = _uiState.value.query
        if (previous == query) return
        loadJob?.cancel()
        _uiState.update {
            it.copy(
                query = query,
                // 查询词变化 → 结果与已加载 Tab 全部失效（iOS loadedTabs.removeAll）。
                songs = emptyList(),
                artists = emptyList(),
                albums = emptyList(),
                playlists = emptyList(),
                loadedTabs = emptySet(),
                suggestions = null,
                error = null,
                isSearching = false,
                // 回到「输入中」态：显联想而非结果（官方：改字即回下拉建议）。
                hasSearched = false,
            )
        }
        if (query.isNotBlank()) suggest(query)
    }

    /**
     * 提交搜索（官方：点热搜/历史/建议、回车、点搜索按钮均走此路）：
     * 写入搜索历史 → 置查询词 → 触发当前 Tab 检索。
     */
    fun submit(keyword: String) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return
        scope.launch { history?.add(trimmed) }
        setQuery(trimmed)
        search()
    }

    /** 删除单条搜索历史（官方：历史项左滑/长按删除）。 */
    fun removeHistory(keyword: String) {
        scope.launch { history?.remove(keyword) }
    }

    /** 清空搜索历史（官方：历史区标题垃圾桶）。 */
    fun clearHistory() {
        scope.launch { history?.clear() }
    }

    /** 切换结果分区（iOS `model.tab`）：首次进入该 Tab 时按需加载。 */
    fun selectTab(tab: SearchTab) {
        _uiState.update { it.copy(tab = tab) }
        load(tab)
    }

    /** 提交搜索（搜索按钮 / 回车）：加载当前 Tab。 */
    fun search() {
        _uiState.update { it.copy(hasSearched = true) }
        load(_uiState.value.tab)
    }

    /**
     * 按需加载某 Tab（iOS `load(tab:)`）：空查询或已加载则跳过。
     *
     * `ALL` 并发拉四路（limit 与 iOS 一致：songs 12 / 其余 10）；单类型 Tab
     * 单路拉取（songs 100 / 其余 50）。
     */
    fun load(tab: SearchTab) {
        val snapshot = _uiState.value
        val keywords = snapshot.query.trim()
        if (keywords.isEmpty()) return
        if (tab in snapshot.loadedTabs) return

        loadJob = scope.launch {
            _uiState.update { it.copy(isSearching = true, error = null) }
            try {
                when (tab) {
                    SearchTab.ALL -> loadAll(keywords)
                    SearchTab.SONGS -> {
                        val songs = runCatching { api.search(keywords, SearchTab.SONGS.searchType!!, 100) }
                            .getOrNull()?.songs.orEmpty()
                        _uiState.update { it.copy(songs = songs) }
                    }
                    SearchTab.ARTISTS -> {
                        val artists = runCatching { api.search(keywords, SearchTab.ARTISTS.searchType!!, 50) }
                            .getOrNull()?.artists.orEmpty()
                        _uiState.update { it.copy(artists = artists) }
                    }
                    SearchTab.ALBUMS -> {
                        val albums = runCatching { api.search(keywords, SearchTab.ALBUMS.searchType!!, 50) }
                            .getOrNull()?.albums.orEmpty()
                        _uiState.update { it.copy(albums = albums) }
                    }
                    SearchTab.PLAYLISTS -> {
                        val playlists = runCatching { api.search(keywords, SearchTab.PLAYLISTS.searchType!!, 50) }
                            .getOrNull()?.playlists.orEmpty()
                        _uiState.update { it.copy(playlists = playlists) }
                    }
                }
                _uiState.update {
                    it.copy(isSearching = false, loadedTabs = it.loadedTabs + tab)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update {
                    // 原 bug：catch 分支也把 tab 加入 loadedTabs，失败后该 Tab
                    // 永不可重试。失败仅记错误态、不标记已加载，保留重试入口。
                    it.copy(
                        isSearching = false,
                        error = error.message ?: ERROR_SEARCH,
                    )
                }
            }
        }
    }

    /** `ALL`：四路并发（iOS `async let` ×4，同一 scope 内并行）。 */
    private suspend fun loadAll(keywords: String) = coroutineScope {
        val songs = async { runCatching { api.search(keywords, SearchTab.SONGS.searchType!!, 12) }.getOrNull()?.songs }
        val artists = async { runCatching { api.search(keywords, SearchTab.ARTISTS.searchType!!, 10) }.getOrNull()?.artists }
        val albums = async { runCatching { api.search(keywords, SearchTab.ALBUMS.searchType!!, 10) }.getOrNull()?.albums }
        val playlists = async { runCatching { api.search(keywords, SearchTab.PLAYLISTS.searchType!!, 10) }.getOrNull()?.playlists }
        _uiState.update {
            it.copy(
                songs = songs.await().orEmpty(),
                artists = artists.await().orEmpty(),
                albums = albums.await().orEmpty(),
                playlists = playlists.await().orEmpty(),
            )
        }
    }

    private fun suggest(keywords: String) {
        scope.launch {
            val body: SearchSuggestBody? = runCatching { api.searchSuggest(keywords) }.getOrNull()
            // 仅当仍是同一关键字时才写入（避免竞态覆盖）。
            if (_uiState.value.query == keywords) {
                _uiState.update { it.copy(suggestions = body) }
            }
        }
    }

    /** 取消内部协程（页面销毁时由 UI 调用，避免收集器常驻泄漏）。 */
    fun cancel() {
        scope.cancel()
    }

    private companion object {
        const val ERROR_SEARCH = "搜索失败"
    }
}