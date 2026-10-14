// Ported from kumone by missuo (LGPL-3.0)
//
// 首页路由常量（T18 `HomeNavigation.kt`）。
//
// 与 `:app` 的 `Routes` 保持字符串契约（见 `Routes.HOME` 等）。feature 模块不得
// 反向依赖 `:app`，因此本模块自持路由常量，导航目标字符串须与 `:app/Routes` 一致。

package im.missuo.kumone.feature.home

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController

/** 首页 Tab 起始路由（对应 `Routes.HOME`）。 */
const val HOME_ROUTE = "home"

/** 搜索 Tab 路由（对应 `Routes.SEARCH`）。 */
const val SEARCH_ROUTE = "search"

/** 歌单详情路由构造器（对应 `Routes.playlistDetail`）。 */
fun playlistDetailRoute(id: Long): String = "playlist/$id"

/** 专辑详情路由构造器（对应 `Routes.albumDetail`）。 */
fun albumDetailRoute(id: Long): String = "album/$id"

/** 歌手详情路由构造器（对应 `Routes.artistDetail`）。 */
fun artistDetailRoute(id: Long): String = "artist/$id"

/** 每日推荐覆盖层（对应 `Routes.DAILY_SONGS`）。 */
const val DAILY_SONGS_ROUTE = "daily_songs"

/** 最近播放覆盖层（对应 `Routes.RECENT_PLAYS`）。 */
const val RECENT_PLAYS_ROUTE = "recent_plays"

/** 我喜欢的音乐覆盖层（对应 `Routes.FAVORITES`）。 */
const val FAVORITES_ROUTE = "favorites"

/** 音乐云盘覆盖层（对应 `Routes.CLOUD`）。 */
const val CLOUD_ROUTE = "cloud"

/**
 * 导航图占位：本页为 Tab 内容（由 `:app` TabsScreen 直接调用 `*Screen` 渲染），
 * 二级详情统一走 `:feature:detail` 的路由，故此处无独立覆盖层路由。
 */
fun NavGraphBuilder.homeGraph(navController: NavHostController) {
    // no-op：见上方说明。
}
