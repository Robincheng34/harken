// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken.storage.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 最近播放缓存行（P1：`RecentPlaysScreen` 与跨会话「最近播放」上下文）。
 *
 * 只存展示所需字段；完整曲目回放时按 [trackId] 重新拉取 songDetails。
 */
@Entity(tableName = "play_history")
data class PlayHistoryEntity(
    @PrimaryKey val trackId: Long,
    val name: String,
    val artistName: String,
    val albumName: String,
    val coverUrl: String?,
    val durationMS: Long,
    /** 播放完成/跳转时的 epoch 毫秒。 */
    val playedAt: Long,
)
