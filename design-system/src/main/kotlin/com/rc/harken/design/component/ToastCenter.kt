// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken.design.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 居中 Toast（iOS `ToastCenter` 1:1 移植）。
 *
 * 用 [rememberToastState] 创建并在任意可组合处持有；通过 [ToastState.show] 触发，
 * 2 秒后自动隐藏。配合 `ToastCenter(state)` 放在界面最外层。
 */
class ToastState(private val scope: CoroutineScope) {
    var message: String? by mutableStateOf(null)
        private set

    // 自增 token：同一文案连续 show 时（原 bug：延时清除按 message == text
    // 判断，首个定时器会把新 Toast 提前清掉），只有最新一次 show 的定时器
    // 才能清除。
    private var token = 0L

    fun show(text: String, durationMillis: Long = 2000L) {
        message = text
        val current = ++token
        scope.launch {
            delay(durationMillis)
            if (token == current) message = null
        }
    }
}

@Composable
fun rememberToastState(scope: CoroutineScope = rememberCoroutineScope()): ToastState =
    remember(scope) { ToastState(scope) }

@Composable
fun ToastCenter(
    state: ToastState,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .wrapContentSize(Alignment.Center),
    ) {
        AnimatedVisibility(
            visible = state.message != null,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Surface(
                tonalElevation = 4.dp,
                shadowElevation = 6.dp,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)),
            ) {
                Text(
                    text = state.message.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .wrapContentSize(Alignment.Center)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
    }
}
