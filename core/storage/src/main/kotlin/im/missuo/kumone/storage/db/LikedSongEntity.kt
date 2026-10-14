// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.storage.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 红心歌曲缓存行（P1：`LikedSongsScreen` 离线可见）。
 *
 * 与 `likedTrackIDs`（服务端权威）互补：本地缓存保证未登录/弱网下
 * 「我喜欢的音乐」仍可展示。
 */
@Entity(tableName = "liked_songs")
data class LikedSongEntity(
    @PrimaryKey val trackId: Long,
    val name: String,
    val artistName: String,
    val albumName: String,
    val coverUrl: String?,
    /** 红心时间的 epoch 毫秒。 */
    val likedAt: Long,
)
