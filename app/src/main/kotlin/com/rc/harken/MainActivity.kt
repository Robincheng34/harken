// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken

import android.content.pm.PackageManager
import androidx.compose.runtime.CompositionLocalProvider
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.rc.harken.di.AppGraph
import com.rc.harken.ui.HarkenApp

/** 设计基准宽（与主流 6.7" 机型一致；密度按屏宽换算到该值）。 */
private const val DESIGN_WIDTH_DP = 411f

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
            // 屏幕宽度适配（今日头条方案）:同物理宽度的设备因出厂密度档位不同
            // （如 OnePlus 7T Pro 3.5x=411dp vs vivo V2408A 4.0x=360dp），固定 dp
            // 布局在窄 dp 屏上会整体偏大（卡片/间距观感放大 ~14%）。按屏宽重算
            // 密度，把设计宽统一到 DESIGN_WIDTH_DP，全 App 在所有设备上渲染
            // 比例一致；sp 走系统 fontScale 不受影响。
            val density = LocalDensity.current
            val screenWidthPx = LocalConfiguration.current.screenWidthDp * density.density
            val adapted = Density(
                density = (screenWidthPx / DESIGN_WIDTH_DP).coerceIn(2f, 6f),
                fontScale = density.fontScale,
            )
            CompositionLocalProvider(LocalDensity provides adapted) {
                HarkenApp(graph = graph)
            }
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
