// Ported from kumone by missuo (LGPL-3.0)
//
// 详情导航图（T21/T22 `DetailNavigation.kt`）。
//
// 暴露 [detailGraph] 供 `:app` 根 NavHost 装配；三类详情共用 `{id}` 参数。
// 路由常量取自 `:design-system` 的 `Destinations`（唯一真源，与 `:app/Routes`
// 委托引用同一份字符串），feature 模块因此无需反向依赖 `:app`。

package im.missuo.kumone.feature.detail

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import im.missuo.kumone.design.navigation.Destinations

/**
 * 详情相关路由：歌单 / 专辑 / 歌手三类详情。
 */
fun NavGraphBuilder.detailGraph(navController: NavHostController) {
    composable(
        route = Destinations.PLAYLIST_DETAIL,
        arguments = listOf(navArgument(Destinations.ARG_ID) { type = NavType.LongType }),
    ) { entry ->
        PlaylistDetailScreen(
            playlistID = entry.longId(),
            onBack = { navController.popBackStack() },
        )
    }

    composable(
        route = Destinations.ALBUM_DETAIL,
        arguments = listOf(navArgument(Destinations.ARG_ID) { type = NavType.LongType }),
    ) { entry ->
        AlbumDetailScreen(
            albumID = entry.longId(),
            onBack = { navController.popBackStack() },
        )
    }

    composable(
        route = Destinations.ARTIST_DETAIL,
        arguments = listOf(navArgument(Destinations.ARG_ID) { type = NavType.LongType }),
    ) { entry ->
        ArtistDetailScreen(
            artistID = entry.longId(),
            onBack = { navController.popBackStack() },
            onOpenAlbum = { navController.navigate(Destinations.albumDetail(it)) },
        )
    }
}

/** 从路由参数读取 `id`（缺失时回落 0）。 */
private fun androidx.navigation.NavBackStackEntry.longId(): Long =
    arguments?.getLong(Destinations.ARG_ID) ?: 0L
