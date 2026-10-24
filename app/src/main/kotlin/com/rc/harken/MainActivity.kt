// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.rc.harken.di.AppGraph
import com.rc.harken.ui.HarkenApp

/**
 * 单 Activity 宿主：edge-to-edge + Compose 内容（`HarkenApp`）。
 *
 * 冷启动流程：Manifest 上挂 `Theme.Harken.Launch`（windowBackground = 品牌启动图），
 * [onCreate] 在 super 之前 `setTheme` 切回正常主题，窗口背景白屏由此消除；
 * 品牌图 → 首页的过渡由 `HarkenApp` 内的 Compose 启动层完成。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须在 super.onCreate 之前：窗口内容填充前切回正常主题，
        // 否则 Launch 主题的全屏 windowBackground 会驻留整个生命周期。
        setTheme(R.style.Theme_Harken)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        val graph = AppGraph.get(this)
        setContent {
            HarkenApp(graph = graph)
        }
    }

    /**
     * Android 13+ 通知属运行时权限：不申请则媒体通知被静默抑制——锁屏/通知栏/
     * 蓝牙表盘的播放控制入口全部消失（vivo 这类激进后台管控的 ROM 还会因此
     * 更早冻结进程）。launch 时请求一次；用户拒绝后由系统管理「不再询问」。
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}
