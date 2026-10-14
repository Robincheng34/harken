// Ported from kumone by missuo (LGPL-3.0)
//
// 搜索页路由常量（T20 `SearchNavigation.kt`）。
//
// 与 `:app` 的 `Routes.SEARCH` 保持字符串契约（feature 模块不得反向依赖 `:app`）。

package im.missuo.kumone.feature.search

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController

/** 搜索页 Tab 起始路由（对应 `Routes.SEARCH`）。 */
const val SEARCH_ROUTE = "search"

/**
 * 导航图占位：本页为 Tab 内容（由 `:app` TabsScreen 直接调用 `*Screen` 渲染），
 * 二级详情统一走 `:feature:detail` 的路由，故此处无独立覆盖层路由。
 */
fun NavGraphBuilder.searchGraph(navController: NavHostController) {
    // no-op：见上方说明。
}
