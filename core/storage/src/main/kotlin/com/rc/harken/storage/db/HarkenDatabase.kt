// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken.storage.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Harken 本地缓存数据库（P1）。
 *
 * 退出登录时**保留**本库（架构文档 §9 假设 5）：下载清单属于本地数据，
 * 不随账号清除。
 *
 * v3：删除从未接线的 liked_songs / play_history 两张表（规划中的「离线红心 /
 * 离线播放历史」最终全部走服务端接口，两张表自建库起从未写入过）。走正式
 * Migration 而非破坏性迁移，保住用户已下载歌曲的清单记录。
 */
@Database(
    entities = [
        DownloadedSongEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class HarkenDatabase : RoomDatabase() {

    abstract fun downloadedSongDao(): DownloadedSongDao

    companion object {
        fun build(context: Context): HarkenDatabase =
            Room.databaseBuilder(context, HarkenDatabase::class.java, DATABASE_NAME)
                .addMigrations(MIGRATION_2_3)
                .build()

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS liked_songs")
                db.execSQL("DROP TABLE IF EXISTS play_history")
            }
        }

        private const val DATABASE_NAME = "harken-cache.db"
    }
}
