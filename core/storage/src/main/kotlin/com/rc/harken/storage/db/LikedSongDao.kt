// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken.storage.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** 红心歌曲缓存访问（P1）。 */
@Dao
interface LikedSongDao {

    @Query("SELECT * FROM liked_songs ORDER BY likedAt DESC")
    fun observeAll(): Flow<List<LikedSongEntity>>

    @Query("SELECT COUNT(*) FROM liked_songs")
    fun observeCount(): Flow<Int>

    @Query("SELECT EXISTS(SELECT 1 FROM liked_songs WHERE trackId = :trackId)")
    fun observeIsLiked(trackId: Long): Flow<Boolean>

    @Upsert
    suspend fun upsert(entry: LikedSongEntity)

    @Query("DELETE FROM liked_songs WHERE trackId = :trackId")
    suspend fun delete(trackId: Long)

    @Query("DELETE FROM liked_songs")
    suspend fun clear()
}
