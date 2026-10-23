// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken.playback

/**
 * Where the current queue came from (iOS `PlayContext`).
 *
 * Used for the "recently played" section of the dock menu and as the
 * scrobbling source id.
 */
data class PlayContext(val kind: Kind, val id: Long, val name: String) {

    enum class Kind {
        PLAYLIST,
        ALBUM,
        ARTIST,
        DAILY,
        CLOUD,
        RECENTS,
        HEARTBEAT,
        FM,
    }

    companion object {
        fun playlist(id: Long, name: String): PlayContext =
            PlayContext(Kind.PLAYLIST, id, name)

        fun album(id: Long, name: String): PlayContext =
            PlayContext(Kind.ALBUM, id, name)

        fun artist(id: Long, name: String): PlayContext =
            PlayContext(Kind.ARTIST, id, name)

        val daily: PlayContext = PlayContext(Kind.DAILY, 0L, "每日推荐")

        val cloud: PlayContext = PlayContext(Kind.CLOUD, 0L, "音乐云盘")

        val recents: PlayContext = PlayContext(Kind.RECENTS, 0L, "最近播放")

        val heartbeat: PlayContext = PlayContext(Kind.HEARTBEAT, 0L, "心动模式")

        val fm: PlayContext = PlayContext(Kind.FM, 0L, "私人漫游")
    }
}
