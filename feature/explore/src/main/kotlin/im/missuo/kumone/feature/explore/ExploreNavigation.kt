// Ported from kumone by missuo (LGPL-3.0)
//
// 发现页路由常量（T19 `ExploreNavigation.kt`）。
//
// 与 `:app` 的 `Routes.EXPLORE` 保持字符串契约（feature 模块不得反向依赖 `:app`）。

package im.missuo.kumone.feature.explore

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController

/** 发现页 Tab 起始路由（对应 `Routes.EXPLORE`）。 */
const val EXPLORE_ROUTE = "explore"

/**
 * 导航图占位：本页为 Tab 内容（由 `:app` TabsScreen 直接调用 `*Screen` 渲染），
 * 二级详情统一走 `:feature:detail` 的路由，故此处无独立覆盖层路由。
 */
fun NavGraphBuilder.exploreGraph(navController: NavHostController) {
    // no-op：见上方说明。
}
