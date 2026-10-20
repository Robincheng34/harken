// Ported from kumone by missuo (LGPL-3.0)
//
// 共享路由目标（架构文档 §3 `ui/Routes.kt` 的下放版本）。
//
// 原 `Routes` 位于 `:app`，但 feature 模块不得反向依赖 `:app`（会形成
// `:app -> :feature:* -> :app` 循环）。因此把全部路由常量与构造器下放到本模块
// （所有 feature 已依赖 `:design-system`），由 `:app/Routes` 委托引用，保证唯一真源、
// 杜绝字符串漂移。feature 模块统一通过 [Destinations] 发起跨界面导航。

package im.missuo.kumone.design.navigation

/** 全应用路由目标（字符串契约与 `:app/Routes` 一致）。 */
object Destinations {

    // 底部 Tab 起始路由。
    const val HOME = "home"
    const val EXPLORE = "explore"
    const val SEARCH = "search"
    const val FM = "fm"
    const val LIBRARY = "library"

    // 覆盖层根。
    const val TABS = "tabs"
    const val NONE = "none"
    const val LOGIN = "login"
    const val NOW_PLAYING = "now_playing"
    const val SETTINGS = "settings"

    // 详情类（含参数）。
    const val PLAYLIST_DETAIL = "playlist/{id}"
    const val ALBUM_DETAIL = "album/{id}"
    const val ARTIST_DETAIL = "artist/{id}"

    // P1 覆盖层。
    const val DAILY_SONGS = "daily_songs"
    const val RECENT_PLAYS = "recent_plays"
    const val FAVORITES = "favorites"
    const val CLOUD = "cloud"

    /** 已下载（离线播放闭环的已下载管理页，覆盖层）。 */
    const val DOWNLOADED = "downloaded"

    /** 扫一扫（我的页快捷入口，纯本地相机能力，覆盖层）。 */
    const val SCAN = "scan"

    /** 歌曲信息页（播放页「歌曲信息」入口的全屏覆盖层，带 {id} 参数）。 */
    const val SONG_INFO = "song_info/{id}"

    /** 我的页左侧抽屉（红底新样式，左上汉堡键 / 「更多」入口，覆盖层）。 */
    const val LIBRARY_DRAWER = "library_drawer"

    // 搜索落地页快捷入口（覆盖层）。
    const val ARTISTS = "artists"
    const val GENRE = "genre"

    /** 详情路由参数名（与 NavBackStackEntry.arguments 键一致）。 */
    const val ARG_ID = "id"

    /** 歌单详情路由构造器。 */
    fun playlistDetail(id: Long): String = "playlist/$id"

    /** 专辑详情路由构造器。 */
    fun albumDetail(id: Long): String = "album/$id"

    /** 歌手详情路由构造器。 */
    fun artistDetail(id: Long): String = "artist/$id"

    /** 歌曲信息页路由构造器。 */
    fun songInfo(id: Long): String = "song_info/$id"
}
