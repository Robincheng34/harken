// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.storage.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Kumone 本地缓存数据库（P1）。
 *
 * 退出登录时**保留**本库（架构文档 §9 假设 5）：播放历史与红心缓存属于
 * 本地数据，不随账号清除。
 */
@Database(
    entities = [PlayHistoryEntity::class, LikedSongEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class KumoneDatabase : RoomDatabase() {

    abstract fun playHistoryDao(): PlayHistoryDao

    abstract fun likedSongDao(): LikedSongDao

    companion object {
        fun build(context: Context): KumoneDatabase =
            Room.databaseBuilder(context, KumoneDatabase::class.java, DATABASE_NAME)
                .fallbackToDestructiveMigration()
                .build()

        private const val DATABASE_NAME = "kumone-cache.db"
    }
}
