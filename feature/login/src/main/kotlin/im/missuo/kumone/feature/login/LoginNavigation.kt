// Ported from kumone by missuo (LGPL-3.0)
//
// 登录导航图（T16 `LoginNavigation.kt`）。
//
// 暴露 [loginGraph] 供 `:app` 的根 NavHost 装配；仅含扫码登录覆盖路由。
//
// 架构铁律：feature 模块不得反向依赖 `:app`（否则形成 `:app -> :feature:* -> :app`
// 循环）。因此登录路由常量在本模块定义，与 `:app` 的 `Routes.LOGIN` 保持字符串契约。

package im.missuo.kumone.feature.login

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import im.missuo.kumone.design.composition.LocalOnLoginConfirmed

/** 登录覆盖层路由（与 :app `Routes.LOGIN` 的字符串契约）。 */
const val LOGIN_ROUTE = "login"

/** 登录相关路由（扫码登录面板）。 */
fun NavGraphBuilder.loginGraph(navController: NavHostController) {
    composable(LOGIN_ROUTE) {
        // CompositionLocal.current 只能在 @Composable 上下文读取：先在此读出，
        // 再闭包传入非 Composable 的 onConfirmed 回调。
        val onLoginConfirmed = LocalOnLoginConfirmed.current
        LoginSheet(
            onDismiss = { navController.popBackStack() },
            onConfirmed = { onLoginConfirmed?.invoke() },
        )
    }
}
