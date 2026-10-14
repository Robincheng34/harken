// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.storage.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** 最近播放缓存访问（P1）。 */
@Dao
interface PlayHistoryDao {

    @Query("SELECT * FROM play_history ORDER BY playedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<PlayHistoryEntity>>

    @Upsert
    suspend fun upsert(entry: PlayHistoryEntity)

    @Query("DELETE FROM play_history WHERE trackId = :trackId")
    suspend fun delete(trackId: Long)

    @Query("DELETE FROM play_history")
    suspend fun clear()

    /** 只保留最近 [keep] 条，防止缓存无限增长。 */
    @Query(
        """
        DELETE FROM play_history WHERE trackId NOT IN (
            SELECT trackId FROM play_history ORDER BY playedAt DESC LIMIT :keep
        )
        """,
    )
    suspend fun trim(keep: Int = 100)
}
