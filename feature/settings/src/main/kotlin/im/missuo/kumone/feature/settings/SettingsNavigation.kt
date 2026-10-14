// Ported from kumone by missuo (LGPL-3.0)
//
// 设置导航图（T28 `SettingsNavigation.kt`）。
//
// 设置页作为覆盖层路由（从「我的」页进入）。

package im.missuo.kumone.feature.settings

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import im.missuo.kumone.design.navigation.Destinations

/** 设置相关覆盖层路由。 */
fun NavGraphBuilder.settingsGraph(navController: NavHostController) {
    composable(Destinations.SETTINGS) {
        SettingsScreen(onBack = { navController.popBackStack() })
    }
}
