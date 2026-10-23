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
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = AppGraph.get(this)
        setContent {
            HarkenApp(graph = graph)
        }
    }
}
