// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.storage.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 已下载歌曲清单行（离线播放闭环的持久层）。
 *
 * 主键 `id` 为 `"${trackId}_$level"`，同一首歌按音质分别记录；文件本体落在
 * `getExternalFilesDir(DIRECTORY_MUSIC)/kumone/` 下，由 [DownloadRepository] 管理，
 * 此表只保留展示与定位所需的元数据。
 */
@Entity(tableName = "downloaded_songs")
data class DownloadedSongEntity(
    @PrimaryKey val id: String, // "${trackId}_$level"
    val trackId: Long,
    val level: String,
    val fileName: String, // 形如 "${trackId}_${level}.mp3"
    val title: String,
    val artist: String,
    val album: String,
    val albumPicUrl: String?,
    val durationMS: Long,
    val sizeBytes: Long,
    val downloadedAt: Long,
)
