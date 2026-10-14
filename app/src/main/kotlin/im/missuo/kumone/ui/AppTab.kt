// Ported from kumone by missuo (LGPL-3.0)
//
// 底部 Tab 定义（架构文档 §3 `ui/AppTab.kt` 1:1 移植）。

package im.missuo.kumone.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 五个底部 Tab（首页 / 发现 / 搜索 / 漫游 / 我的）。
 */
enum class AppTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    HOME(Routes.HOME, "首页", Icons.Filled.Home),
    EXPLORE(Routes.EXPLORE, "发现", Icons.Filled.Explore),
    SEARCH(Routes.SEARCH, "搜索", Icons.Filled.Search),
    FM(Routes.FM, "漫游", Icons.Filled.Radio),
    LIBRARY(Routes.LIBRARY, "我的", Icons.Filled.Person),
}
