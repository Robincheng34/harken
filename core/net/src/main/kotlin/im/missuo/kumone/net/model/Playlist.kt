// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.net.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/** Creator of a playlist. */
@Serializable
data class PlaylistCreator(
    @Serializable(with = LenientLongSerializer::class)
    val userId: Long = 0,
    val nickname: String = "",
    val avatarUrl: String? = null,
)

/**
 * A playlist as it appears in grids and sidebars. Tolerates the several cover
 * field names and numeric types NetEase uses across endpoints.
 */
@Serializable
data class PlaylistSummary(
    @Serializable(with = LenientLongSerializer::class)
    val id: Long,
    val name: String = "",
    @JsonNames("picUrl", "coverImgUrl")
    val coverURL: String? = null,
    @JsonNames("playCount", "playcount")
    @Serializable(with = LenientIntSerializer::class)
    val playCount: Int = 0,
    @Serializable(with = LenientIntSerializer::class)
    val trackCount: Int = 0,
    @Serializable(with = LenientNullableLongSerializer::class)
    val updateTime: Long? = null,
    val copywriter: String? = null,
    val creator: PlaylistCreator? = null,
    @Serializable(with = LenientIntSerializer::class)
    val specialType: Int = 0,
    @Serializable(with = LenientIntSerializer::class)
    val privacy: Int = 0,
    val subscribed: Boolean = false,
) {
    /** The auto-created "我喜欢的音乐" playlist. */
    val isLikedSongsList: Boolean get() = specialType == 5
}

/** A bare track id reference inside [PlaylistDetail.trackIds]. */
@Serializable
data class TrackIDRef(
    @Serializable(with = LenientLongSerializer::class)
    val id: Long,
)

/** Full playlist detail (`/v6/playlist/detail`, n=100000, s=8). */
@Serializable
data class PlaylistDetail(
    @Serializable(with = LenientLongSerializer::class)
    val id: Long,
    val name: String = "",
    val coverImgUrl: String? = null,
    val creator: PlaylistCreator? = null,
    val description: String? = null,
    @Serializable(with = LenientIntSerializer::class)
    var trackCount: Int = 0,
    @Serializable(with = LenientIntSerializer::class)
    val playCount: Int = 0,
    @Serializable(with = LenientIntSerializer::class)
    val subscribedCount: Int = 0,
    var subscribed: Boolean = false,
    var trackIds: List<TrackIDRef> = emptyList(),
    var tracks: List<Track> = emptyList(),
    @Serializable(with = LenientIntSerializer::class)
    val specialType: Int = 0,
    @Serializable(with = LenientLongSerializer::class)
    val updateTime: Long = 0,
) {
    companion object {
        /** Builds a detail stub from a grid [PlaylistSummary] (iOS `init(summary:)`). */
        fun fromSummary(summary: PlaylistSummary): PlaylistDetail = PlaylistDetail(
            id = summary.id,
            name = summary.name,
            coverImgUrl = summary.coverURL,
            creator = summary.creator,
            description = summary.copywriter,
            trackCount = summary.trackCount,
            playCount = summary.playCount,
            subscribedCount = 0,
            subscribed = summary.subscribed,
            trackIds = emptyList(),
            tracks = emptyList(),
            specialType = summary.specialType,
            updateTime = summary.updateTime ?: 0,
        )
    }
}

/** `/v6/playlist/detail` envelope. */
@Serializable
data class PlaylistDetailResponse(
    val playlist: PlaylistDetail,
    val privileges: List<TrackPrivilege> = emptyList(),
)

/** Lightweight name + cover body (`playlistBrief`). */
@Serializable
data class PlaylistBriefBody(
    @Serializable(with = LenientLongSerializer::class)
    val id: Long,
    val name: String? = null,
    val coverImgUrl: String? = null,
)

/** `/user/playlist` envelope. */
@Serializable
data class UserPlaylistsResponse(
    val playlist: List<PlaylistSummary> = emptyList(),
    val more: Boolean? = null,
)

/** `/playlist/list` envelope. */
@Serializable
data class TopPlaylistResponse(
    val playlists: List<PlaylistSummary> = emptyList(),
    @Serializable(with = LenientNullableIntSerializer::class)
    val total: Int? = null,
    val more: Boolean? = null,
)

/** `/playlist/highquality/list` envelope. */
@Serializable
data class HighQualityResponse(
    val playlists: List<PlaylistSummary> = emptyList(),
    @Serializable(with = LenientNullableLongSerializer::class)
    val lasttime: Long? = null,
    val more: Boolean? = null,
)
