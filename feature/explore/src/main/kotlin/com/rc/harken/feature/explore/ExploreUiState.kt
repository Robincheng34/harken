// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 发现页 UI 状态（T19 `ExploreUiState.kt`，iOS `ExploreViewModel` 状态 1:1 移植）。
//
// 单列表 [playlists] 承载当前分类的歌单结果（排行榜分类除外，走 [toplists]）；
// [hasMore] 控制无限滚动是否继续加载（iOS `hasMore`）。

package com.rc.harken.feature.explore

import com.rc.harken.net.model.PlaylistSummary
import com.rc.harken.net.model.ToplistItem

/** 发现页全部分类（iOS `ExploreViewModel.categories`，27 项）。 */
val EXPLORE_CATEGORIES: List<String> = listOf(
    "全部", "推荐歌单", "精品歌单", "排行榜", "官方",
    "华语", "流行", "摇滚", "民谣", "电子", "轻音乐", "说唱", "爵士", "古典",
    "影视原声", "ACG", "古风", "怀旧", "治愈", "放松", "伤感", "快乐",
    "学习", "工作", "运动", "驾车", "夜晚",
)

/** 排行榜分类（一次性拉取全部榜单，无分页）。 */
const val CATEGORY_TOPLISTS = "排行榜"

/** 推荐歌单分类（一次性拉取 100 条，无分页）。 */
const val CATEGORY_RECOMMENDED = "推荐歌单"

/** 精品歌单分类（按 `before` 游标分页）。 */
const val CATEGORY_HIGH_QUALITY = "精品歌单"

/** 默认分类。 */
const val CATEGORY_ALL = "全部"

/** 默认每页条数（iOS `topPlaylists` limit 50）。 */
const val EXPLORE_PAGE_SIZE = 50

/** offset 分页上限（iOS `offset < 500`）。 */
const val EXPLORE_MAX_OFFSET = 500

/** 发现页聚合状态。 */
data class ExploreUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    /** 全部分类（iOS `categories`）。 */
    val categories: List<String> = EXPLORE_CATEGORIES,
    /** 当前选中的分类。 */
    val selectedCategory: String = CATEGORY_ALL,
    /** 当前分类下的歌单（排行榜分类为空）。 */
    val playlists: List<PlaylistSummary> = emptyList(),
    /** 排行榜分类下的榜单列表。 */
    val toplists: List<ToplistItem> = emptyList(),
    /** 是否还有更多（控制无限滚动）。 */
    val hasMore: Boolean = true,
)
