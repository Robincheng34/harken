// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 发现页 ViewModel（T19 `ExploreViewModel.kt`，iOS `ExploreView` `ExploreViewModel` 1:1 移植）。
//
// 单列表 + 无限滚动（iOS `loadMore`）：
//  - 「排行榜」：一次性 `toplists()`，`hasMore = false`；
//  - 「推荐歌单」：一次性 `personalizedPlaylists(limit = 100)`，`hasMore = false`；
//  - 「精品歌单」：`highQualityPlaylists(before = 游标)` 游标分页；
//  - 其余分类：`topPlaylists(category, offset)` offset 分页（每页 50，上限 500）。
// 切换分类取消进行中的加载并重置分页游标（iOS `loadTask?.cancel()`）。
// 纯 Kotlin + 协程，可纯 JVM 单测（见 `ExploreViewModelTest`）。

package com.rc.harken.feature.explore

import com.rc.harken.net.api.NeteaseApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ExploreViewModel(
    private val api: NeteaseApi,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val _uiState: MutableStateFlow<ExploreUiState> = MutableStateFlow(ExploreUiState())
    val uiState: StateFlow<ExploreUiState> = _uiState.asStateFlow()

    /** 进行中的加载任务（切换分类时取消，iOS `loadTask?.cancel()`）。 */
    private var loadJob: Job? = null

    /** offset 分页游标。 */
    private var offset = 0

    /** 精品歌单 `before` 游标。 */
    private var highQualityBefore = 0L

    /** 进入页面时的首次加载（iOS `.task`：列表为空才加载）。 */
    fun load() {
        if (_uiState.value.playlists.isNotEmpty() || _uiState.value.toplists.isNotEmpty()) return
        loadMore()
    }

    /** 下拉刷新 / 重按 Tab 触发:清空当前分类数据并重新走完整加载。 */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    fun refresh() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        // 取消在途加载,清空当前分类数据与分页游标,再走一次 loadMore
        // (它按 selectedCategory 分支取数,游标已在下方重置)。
        loadJob?.cancel()
        _uiState.update {
            it.copy(
                playlists = emptyList(),
                toplists = emptyList(),
                hasMore = true,
                error = null,
                isLoading = false,
            )
        }
        offset = 0
        highQualityBefore = 0L
        scope.launch {
            try {
                loadMore()
                loadJob?.join()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /**
     * 切换分类（iOS `select(_:)`）：相同分类且已有数据时忽略；否则重置列表与
     * 分页游标并重新加载。
     */
    fun select(category: String) {
        val current = _uiState.value
        if (category == current.selectedCategory && current.playlists.isNotEmpty()) return
        _uiState.update {
            it.copy(
                selectedCategory = category,
                playlists = emptyList(),
                toplists = emptyList(),
                hasMore = true,
                error = null,
            )
        }
        offset = 0
        highQualityBefore = 0L
        loadJob?.cancel()
        loadMore()
    }

    /** 加载下一页（iOS `loadMore`）；进行中或无更多时忽略。 */
    fun loadMore() {
        val snapshot = _uiState.value
        if (snapshot.isLoading || !snapshot.hasMore) return
        loadJob = scope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (snapshot.selectedCategory) {
                CATEGORY_TOPLISTS -> {
                    val lists = runCatching { api.toplists() }.getOrNull()
                    if (lists != null) {
                        _uiState.update { it.copy(toplists = lists, hasMore = false, error = null) }
                    } else {
                        // 原 bug：失败也置 hasMore=false，错误后永久失去重试入口；
                        // 失败仅记错误、保留 hasMore 供重试。
                        _uiState.update { it.copy(error = ERROR_NETWORK) }
                    }
                }

                CATEGORY_RECOMMENDED -> {
                    val result = runCatching { api.personalizedPlaylists(limit = 100) }.getOrNull()
                    if (result != null) {
                        _uiState.update { it.copy(playlists = result, hasMore = false, error = null) }
                    } else {
                        // 原 bug：失败也置 hasMore=false；保留 hasMore 供重试。
                        _uiState.update { it.copy(error = ERROR_NETWORK) }
                    }
                }

                CATEGORY_HIGH_QUALITY -> {
                    val result = runCatching {
                        api.highQualityPlaylists(before = highQualityBefore)
                    }.getOrNull()
                    if (result != null) {
                        val existing = _uiState.value.playlists.mapTo(HashSet()) { it.id }
                        val appended = result.playlists.filter { existing.add(it.id) }
                        highQualityBefore = result.lasttime ?: 0L
                        _uiState.update {
                            it.copy(
                                playlists = it.playlists + appended,
                                hasMore = result.more ?: false,
                                error = null,
                            )
                        }
                    } else {
                        // 原 bug：分页失败也置 hasMore=false，永久关闭加载更多；
                        // 失败保留 hasMore（仅成功且无更多数据时才置 false）。
                        _uiState.update { it.copy(error = ERROR_NETWORK) }
                    }
                }

                else -> {
                    val category = snapshot.selectedCategory
                    val result = runCatching {
                        api.topPlaylists(category = category, offset = offset)
                    }.getOrNull()
                    if (result != null) {
                        val existing = _uiState.value.playlists.mapTo(HashSet()) { it.id }
                        val appended = result.playlists.filter { existing.add(it.id) }
                        offset += EXPLORE_PAGE_SIZE
                        _uiState.update {
                            it.copy(
                                playlists = it.playlists + appended,
                                // iOS: hasMore = (result.more ?? false) && offset < 500
                                hasMore = (result.more ?: false) && offset < EXPLORE_MAX_OFFSET,
                                error = null,
                            )
                        }
                    } else {
                        // 原 bug：分页失败也置 hasMore=false；保留 hasMore 供重试。
                        _uiState.update { it.copy(error = ERROR_NETWORK) }
                    }
                }
            }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    /** 取消内部协程（页面销毁时由 UI 调用，避免收集器常驻泄漏）。 */
    fun cancel() {
        scope.cancel()
    }

    private companion object {
        const val ERROR_NETWORK = "网络连接失败"
    }
}
