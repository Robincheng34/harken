// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import im.missuo.kumone.di.AppGraph
import im.missuo.kumone.ui.KumoneApp

/**
 * 单 Activity 宿主：edge-to-edge + Compose 内容（`KumoneApp`）。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = AppGraph.get(this)
        setContent {
            KumoneApp(graph = graph)
        }
    }
}
