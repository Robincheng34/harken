// Ported from kumone by missuo (LGPL-3.0)
//
// 搜索页路由常量（T20 `SearchNavigation.kt`）。
//
// 与 `:app` 的 `Routes.SEARCH` 保持字符串契约（feature 模块不得反向依赖 `:app`）。

package im.missuo.kumone.feature.search

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import im.missuo.kumone.design.navigation.Destinations
import im.missuo.kumone.feature.search.pages.ArtistsScreen
import im.missuo.kumone.feature.search.pages.GenreScreen

/** 搜索页 Tab 起始路由（对应 `Routes.SEARCH`）。 */
const val SEARCH_ROUTE = "search"

/**
 * 搜索相关覆盖层路由。
 *
 * 搜索页本身是 Tab 内容（由 `:app` TabsScreen 直接调用 `SearchScreen` 渲染），
 * 二级详情统一走 `:feature:detail` 的路由；此处只装配搜索落地页**快捷入口**
 * 的两个二级页：歌手榜 [Destinations.ARTISTS] 与曲风 [Destinations.GENRE]。
 *
 * 本函数由 `:app` 的 KumoneApp 根 NavHost 调用，因此这两条路由注册在**覆盖层**
 * 导航栈上，不影响底部 Tab 内部状态。
 */
fun NavGraphBuilder.searchGraph(navController: NavHostController) {
    composable(Destinations.ARTISTS) {
        ArtistsScreen(
            onBack = { navController.popBackStack() },
            onOpenArtist = { navController.navigate(Destinations.artistDetail(it)) },
        )
    }
    composable(Destinations.GENRE) {
        GenreScreen(
            onBack = { navController.popBackStack() },
            onOpenPlaylist = { navController.navigate(Destinations.playlistDetail(it)) },
        )
    }
}
