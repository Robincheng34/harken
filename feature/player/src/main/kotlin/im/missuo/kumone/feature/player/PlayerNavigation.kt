// Ported from kumone by missuo (LGPL-3.0)
//
// 播放页导航图（T25–T27 `PlayerNavigation.kt`）。
//
// 仅含全屏播放页覆盖层路由；迷你播放条由 `:app` 常驻挂载，不占路由。

package im.missuo.kumone.feature.player

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import im.missuo.kumone.design.navigation.Destinations

/** 播放相关覆盖层路由。 */
fun NavGraphBuilder.playerGraph(navController: NavHostController) {
    composable(Destinations.NOW_PLAYING) {
        NowPlayingScreen(onBack = { navController.popBackStack() })
    }
}
