// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken.storage.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** 已下载歌曲清单访问（离线播放闭环的持久层）。 */
@Dao
interface DownloadedSongDao {

    @Query("SELECT * FROM downloaded_songs ORDER BY downloadedAt DESC")
    fun observeAll(): Flow<List<DownloadedSongEntity>>

    @Upsert
    suspend fun upsert(entity: DownloadedSongEntity)

    @Query("DELETE FROM downloaded_songs WHERE trackId = :trackId AND level = :level")
    suspend fun delete(trackId: Long, level: String)
}
