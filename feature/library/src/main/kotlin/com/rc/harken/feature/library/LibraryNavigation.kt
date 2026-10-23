// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 我的页导航图（T24 `LibraryNavigation.kt`）。
//
// 我的页本身是 Tab 内容（由 `:app` TabsScreen 调用 `LibraryScreen` 渲染）；此处装配
// 覆盖层路由：每日推荐 / 最近播放 / 我的收藏 / 音乐云盘（T30 实现内容页），
// 以及我的页快捷入口的二级页：扫一扫。

package com.rc.harken.feature.library

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.rc.harken.design.navigation.Destinations
import com.rc.harken.feature.library.pages.CloudScreen
import com.rc.harken.feature.library.pages.DailySongsScreen
import com.rc.harken.feature.library.pages.DownloadedScreen
import com.rc.harken.feature.library.pages.FavoritesScreen
import com.rc.harken.feature.library.pages.RecentPlaysScreen
import com.rc.harken.feature.library.pages.ScanScreen

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
    composable(Destinations.DOWNLOADED) {
        DownloadedScreen(onBack = { navController.popBackStack() })
    }
    // 扫一扫（纯本地相机能力，无网络请求）。
    composable(Destinations.SCAN) {
        ScanScreen(onBack = { navController.popBackStack() })
    }
    // 我的页左侧抽屉：不带默认转场，抽屉自己管滑入/滑出动画。
    composable(
        route = Destinations.LIBRARY_DRAWER,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        LibraryDrawerScreen(
            onClose = { navController.popBackStack() },
            // 先关抽屉再跳转：两者都在同一回调里同步执行，避免抽屉残留在返回栈上。
        // （不能在抽屉内部 close 之后再跳：popBackStack 会销毁该页协程作用域，跳转会被取消。）
        onNavigate = { route ->
            navController.popBackStack()
            navController.navigate(route)
        },
        )
    }
}
