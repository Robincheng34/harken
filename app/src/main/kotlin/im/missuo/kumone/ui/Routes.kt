// Ported from kumone by missuo (LGPL-3.0)
//
// 路由常量（架构文档 §3 `ui/Routes.kt` 1:1 移植）。
//
// 全部字符串真源已下放到 `:design-system` 的 [im.missuo.kumone.design.navigation.Destinations]
// （feature 模块不可反向依赖 `:app`，故路由契约上移）。本对象仅作委托别名，保证既有
// `:app` 代码（KumoneApp / *Graph 装配）无需改动；feature 模块请直接引用 `Destinations`。
//
// 五个底部 Tab 各有一个起始路由；详情/登录/全屏播放/设置等作为根导航的「覆盖层」路由，
// 导航其上不影响 Tab 内部状态（详见 `KumoneApp`）。

package im.missuo.kumone.ui

import im.missuo.kumone.design.navigation.Destinations

object Routes {
    // 底部 Tab 起始路由。
    const val HOME: String = Destinations.HOME
    const val EXPLORE: String = Destinations.EXPLORE
    const val SEARCH: String = Destinations.SEARCH
    const val FM: String = Destinations.FM
    const val LIBRARY: String = Destinations.LIBRARY

    // 覆盖层（根导航上叠加）。
    const val TABS: String = Destinations.TABS
    const val NONE: String = Destinations.NONE
    const val LOGIN: String = Destinations.LOGIN
    const val NOW_PLAYING: String = Destinations.NOW_PLAYING
    const val SETTINGS: String = Destinations.SETTINGS

    // 详情类。
    const val PLAYLIST_DETAIL: String = Destinations.PLAYLIST_DETAIL
    const val ALBUM_DETAIL: String = Destinations.ALBUM_DETAIL
    const val ARTIST_DETAIL: String = Destinations.ARTIST_DETAIL

    // P1 覆盖层。
    const val DAILY_SONGS: String = Destinations.DAILY_SONGS
    const val RECENT_PLAYS: String = Destinations.RECENT_PLAYS
    const val FAVORITES: String = Destinations.FAVORITES
    const val CLOUD: String = Destinations.CLOUD

    /** 已下载（离线播放闭环的已下载管理页）。 */
    const val DOWNLOADED: String = Destinations.DOWNLOADED

    /** 详情路由构造器。 */
    fun playlistDetail(id: Long): String = Destinations.playlistDetail(id)
    fun albumDetail(id: Long): String = Destinations.albumDetail(id)
    fun artistDetail(id: Long): String = Destinations.artistDetail(id)

    const val ARG_ID: String = Destinations.ARG_ID
}
