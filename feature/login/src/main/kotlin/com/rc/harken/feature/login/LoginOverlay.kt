// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken.feature.login

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.rc.harken.design.theme.Layout

/**
 * 登录覆盖层：scrim + 底部卡片（宽度 min(屏宽-32, 380) 居中，底部让开
 * 常驻底栏 chrome 条——chrome 绘制在覆盖层之上且拦截该区域触摸，此前
 * 「取消」按钮恰好落进这个条里点了没反应）。
 *
 * 进出场动画由 overlay NavHost 的转场统一驱动（navEnter/navPopExit，与其他
 * 覆盖层一致）。历史实现自绘 AnimatedVisibility 收起动画 + 路由 pop 两段式：
 * 真机上设置 targetState 后动画与重组均不响应（点 scrim/取消/返回全部无效，
 * logcat 证实 close 已执行），简化为单段导航转场后问题消失。
 */
@Composable
fun LoginOverlay(
    onDismiss: () -> Unit,
    onConfirmed: () -> Unit = {},
) {
    val close: () -> Unit = onDismiss
    BackHandler(onBack = close)

    Box(modifier = Modifier.fillMaxSize()) {
        // scrim：点击空白关闭。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = close,
                ),
        )

        // 底部卡片。
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp)
                .widthIn(max = 380.dp)
                .fillMaxWidth()
                .padding(bottom = Layout.tabBarClearance + Layout.bottomGestureInset() + 4.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f), RoundedCornerShape(28.dp))
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LoginSheetContent(
                onConfirmed = {
                    onConfirmed()
                    close()
                },
                onDismiss = close,
            )
        }
    }
}
