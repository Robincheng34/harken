// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 播放页导航图（T25–T27 `PlayerNavigation.kt`）。
//
// 仅含全屏播放页覆盖层路由；迷你播放条由 `:app` 常驻挂载，不占路由。
// 「歌曲信息」为播放页五图标行入口拉起的全屏二级页（带 {id} 参数）。

package com.rc.harken.feature.player

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.rc.harken.design.navigation.Destinations

/** 播放相关覆盖层路由。 */
fun NavGraphBuilder.playerGraph(navController: NavHostController) {
    composable(Destinations.NOW_PLAYING) {
        NowPlayingScreen(
            onBack = { navController.popBackStack() },
            onOpenSongInfo = { id -> navController.navigate(Destinations.songInfo(id)) },
        )
    }

    composable(
        route = Destinations.SONG_INFO,
        arguments = listOf(navArgument(Destinations.ARG_ID) { type = NavType.LongType }),
    ) { entry ->
        SongInfoScreen(
            trackId = entry.arguments?.getLong(Destinations.ARG_ID) ?: 0L,
            onBack = { navController.popBackStack() },
            onOpenAlbum = { id -> navController.navigate(Destinations.albumDetail(id)) },
        )
    }
}
