// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 漫游导航图（T23 `FmNavigation.kt`）。
//
// 漫游是底部 Tab 之一，其内容由 `:app` 的 `TabsScreen` 直接调用 `FmScreen` 渲染，
// 不占用覆盖层路由（iOS `FMView` 同样没有独立详情路由）。此处保留空 [fmGraph]
// 仅为让 `:app` 统一调用所有 feature 的 graph 装配函数（接口对称、便于未来扩展）。

package com.rc.harken.feature.fm

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController

/** 漫游相关路由（当前为空：漫游页作为 Tab 直接渲染）。 */
fun NavGraphBuilder.fmGraph(navController: NavHostController) {
    // no-op：见文件头说明。
}
