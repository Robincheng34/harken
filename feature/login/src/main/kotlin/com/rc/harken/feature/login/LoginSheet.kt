// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 登录面板（T17 `LoginSheet.kt`：扫码 / 短信验证码双通道）。
//
// 以 ModalBottomSheet 承载登录内容：顶部「登录网易云音乐」标题 + 居中分段选择器
// 切换「扫码登录 / 手机验证码」，下方为 QR / SMS 分区，底部「取消」按钮。
// 布局 1:1 对照 iOS `LoginSheet.swift`（限宽 320、分段 Picker 宽 220、title3 semibold）。
// 登录成功（803 或短信登录）经 [onConfirmed] 回调上抛，由宿主写入登录态并关闭。

package com.rc.harken.feature.login

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rc.harken.design.composition.LocalNeteaseApi
import com.rc.harken.design.theme.glassTokens
import com.rc.harken.feature.login.components.QrCodeView
import com.rc.harken.feature.login.components.SmsCodeSection

/** 登录面板（含 ModalBottomSheet 承载，限宽 320 对照 iOS）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginSheet(
    onDismiss: () -> Unit,
    onConfirmed: () -> Unit = {},
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = glassTokens().fill,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        LoginSheetContent(onConfirmed = onConfirmed, onDismiss = onDismiss)
    }
}

/** 登录内容（不含 ModalBottomSheet，便于复用）。布局 1:1 对照 iOS `LoginSheet`。 */
@Composable
fun LoginSheetContent(
    onConfirmed: () -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    val api = LocalNeteaseApi.current
    requireNotNull(api) { "LocalNeteaseApi not provided" }
    val viewModel = remember(api) { LoginViewModel(api) }
    val uiState: LoginUiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.startQrLogin() }
    LaunchedEffect(uiState.status) {
        if (uiState.status == LoginStatus.Confirmed) {
            onConfirmed()
            onDismiss()
        }
    }

    DisposableEffect(Unit) { onDispose { viewModel.release() } }

    // 页面复显时机恢复 QR 轮询（原 bug：onResume() 从未被调用——后台回收后
    // Waiting 态轮询死亡且刷新按钮不显示）。
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.onResume()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 限宽 320（iOS `.frame(width: 320)`）：宽屏/折叠屏上居中，手机屏幕占满。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(320.dp)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 标题「登录网易云音乐」（iOS `title3.weight(.semibold)`）。
        Text(
            text = stringResource(R.string.login_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )

        // 登录方式分段选择（iOS segmented Picker，宽 220 居中）。
        Box(
            modifier = Modifier
                .width(220.dp)
                .padding(top = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                LoginMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = uiState.mode == mode,
                        onClick = { viewModel.switchMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = LoginMode.entries.size,
                        ),
                        label = {
                            Text(
                                text = when (mode) {
                                    LoginMode.QR -> stringResource(R.string.login_switch_to_qr)
                                    LoginMode.SMS -> stringResource(R.string.login_switch_to_sms)
                                },
                            )
                        },
                    )
                }
            }
        }

        when (uiState.mode) {
            LoginMode.QR -> QrSection(uiState = uiState, viewModel = viewModel)
            LoginMode.SMS -> SmsCodeSection(uiState = uiState, viewModel = viewModel)
        }

        Spacer(modifier = Modifier.height(4.dp))

        // 「取消」按钮（iOS `Button("取消")`，12.5sp 次级样式）。
        TextButton(onClick = onDismiss) {
            Text(
                text = stringResource(R.string.login_cancel),
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 扫码登录分区（原 T16 内容，iOS `qrSection` 1:1）。 */
@Composable
private fun QrSection(
    uiState: LoginUiState,
    viewModel: LoginViewModel,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.login_qr_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val qrUrl = uiState.qrUrl
        if (qrUrl != null) {
            QrCodeView(content = qrUrl, sizeDp = 208)
        } else {
            Box(
                modifier = Modifier
                    .size(208.dp)
                    .fillMaxWidth(),
            )
        }

        Text(
            text = statusText(uiState),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        if (uiState.status == LoginStatus.Expired || uiState.status == LoginStatus.Error) {
            Button(onClick = { viewModel.startQrLogin() }) {
                Icon(imageVector = Icons.Filled.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = stringResource(R.string.login_qr_refresh))
            }
        }
    }
}

@Composable
private fun statusText(uiState: LoginUiState): String = when (uiState.status) {
    LoginStatus.Idle -> stringResource(R.string.login_qr_idle)
    LoginStatus.Loading -> stringResource(R.string.login_qr_loading)
    LoginStatus.Waiting -> stringResource(R.string.login_qr_waiting)
    LoginStatus.Scanned -> stringResource(R.string.login_qr_scanned)
    LoginStatus.Confirmed -> stringResource(R.string.login_qr_confirmed)
    LoginStatus.Expired -> stringResource(R.string.login_qr_expired)
    LoginStatus.Error -> uiState.errorMessage ?: stringResource(R.string.login_failed)
}
