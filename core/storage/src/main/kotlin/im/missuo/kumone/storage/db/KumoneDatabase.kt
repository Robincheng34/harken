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
// v2 升级（新增 downloaded_songs 表）走 fallbackToDestructiveMigration()：
// 仅开发版用户，会清空现有播放历史 / 红心缓存，可接受。
@Database(
    entities = [
        PlayHistoryEntity::class,
        LikedSongEntity::class,
        DownloadedSongEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class KumoneDatabase : RoomDatabase() {

    abstract fun playHistoryDao(): PlayHistoryDao

    abstract fun likedSongDao(): LikedSongDao

    abstract fun downloadedSongDao(): DownloadedSongDao

    companion object {
        fun build(context: Context): KumoneDatabase =
            Room.databaseBuilder(context, KumoneDatabase::class.java, DATABASE_NAME)
                .fallbackToDestructiveMigration()
                .build()

        private const val DATABASE_NAME = "kumone-cache.db"
    }
}
