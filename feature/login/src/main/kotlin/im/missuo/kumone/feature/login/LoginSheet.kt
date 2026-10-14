// Ported from kumone by missuo (LGPL-3.0)
//
// 登录面板（T17 `LoginSheet.kt`：扫码 / 短信验证码双通道）。
//
// 以 ModalBottomSheet 承载登录内容：顶部分段选择器切换「扫码登录 / 手机验证码」。
// 扫码侧展示二维码、轮询状态文案、过期/出错可刷新；短信侧为 [SmsCodeSection]。
// 登录成功（803 或短信登录）经 [onConfirmed] 回调上抛，由宿主写入登录态并关闭。

package im.missuo.kumone.feature.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import im.missuo.kumone.design.composition.LocalNeteaseApi
import im.missuo.kumone.design.theme.glassTokens
import im.missuo.kumone.feature.login.components.QrCodeView
import im.missuo.kumone.feature.login.components.SmsCodeSection

/** 扫码登录面板（含 ModalBottomSheet 承载）。 */
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
        LoginSheetContent(onConfirmed = onConfirmed)
    }
}

/** 登录内容（不含 ModalBottomSheet，便于复用）。 */
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.login_sms_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )

        // 登录方式分段选择（iOS segmented Picker）。
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

        when (uiState.mode) {
            LoginMode.QR -> QrSection(uiState = uiState, viewModel = viewModel)
            LoginMode.SMS -> SmsCodeSection(uiState = uiState, viewModel = viewModel)
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

/** 扫码登录分区（原 T16 内容）。 */
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
            QrCodeView(content = qrUrl, sizeDp = 220)
        } else {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .height(220.dp)
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
