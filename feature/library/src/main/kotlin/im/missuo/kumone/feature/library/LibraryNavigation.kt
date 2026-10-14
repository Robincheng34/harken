// Ported from kumone by missuo (LGPL-3.0)
//
// 我的页导航图（T24 `LibraryNavigation.kt`）。
//
// 我的页本身是 Tab 内容（由 `:app` TabsScreen 调用 `LibraryScreen` 渲染）；此处装配
// P1 覆盖层路由：每日推荐 / 最近播放 / 我的收藏 / 音乐云盘（T30 实现内容页）。

package im.missuo.kumone.feature.library

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import im.missuo.kumone.design.navigation.Destinations
import im.missuo.kumone.feature.library.pages.CloudScreen
import im.missuo.kumone.feature.library.pages.DailySongsScreen
import im.missuo.kumone.feature.library.pages.FavoritesScreen
import im.missuo.kumone.feature.library.pages.RecentPlaysScreen

/** 我的相关覆盖层路由（iOS `SidebarView` 的二级页面）。 */
fun NavGraphBuilder.libraryGraph(navController: NavHostController) {
    composable(Destinations.DAILY_SONGS) {
        DailySongsScreen(onBack = { navController.popBackStack() })
    }
    composable(Destinations.RECENT_PLAYS) {
        RecentPlaysScreen(onBack = { navController.popBackStack() })
    }
    composable(Destinations.FAVORITES) {
        FavoritesScreen(
            onBack = { navController.popBackStack() },
            onOpenAlbum = { navController.navigate(Destinations.albumDetail(it)) },
            onOpenArtist = { navController.navigate(Destinations.artistDetail(it)) },
        )
    }
    composable(Destinations.CLOUD) {
        CloudScreen(onBack = { navController.popBackStack() })
    }
}
