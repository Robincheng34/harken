// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.rc.harken.di.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Harken 倾耳 application entry point.
 *
 * 创建播放通知渠道；初始化 [AppGraph] 并冷启动恢复登录态。
 */
class HarkenApplication : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createPlaybackNotificationChannel()
        val graph = AppGraph.get(this)
        scope.launch { graph.bootstrap() }
        scope.launch { graph.checkForUpdateIfDue() }
    }

    /** Media3 播放通知使用的低重要性渠道（对应 iOS NowPlayingManager 的播放信息）。 */
    private fun createPlaybackNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.playback_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.playback_channel_description)
            setShowBadge(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
        Log.d(TAG, "playback notification channel created")
    }

    companion object {
        private const val TAG = "HarkenApplication"

        /** MediaSessionService 通知渠道 ID（:core:player 的 PlayerService 复用）。 */
        const val CHANNEL_ID: String = "harken_playback"
    }
}
