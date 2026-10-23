// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken

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
        val graph = AppGraph.get(this)
        setContent {
            HarkenApp(graph = graph)
        }
    }
}
