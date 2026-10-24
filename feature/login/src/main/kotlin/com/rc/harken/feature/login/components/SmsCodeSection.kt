// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 短信验证码登录分区（T17 `components/SmsCodeSection.kt`，iOS `LoginSheet.smsSection` 1:1 移植）。
//
// 风控提示条 + 手机号输入 + 验证码输入（带 60s 冷却的获取按钮）+ 登录按钮。
// 全部状态由 [LoginUiState] 驱动，交互经 [LoginViewModel] 回执。

package com.rc.harken.feature.login.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rc.harken.design.theme.BrandGradient
import com.rc.harken.feature.login.LoginUiState
import com.rc.harken.feature.login.LoginViewModel
import com.rc.harken.feature.login.R

/**
 * 短信验证码登录表单。
 *
 * @param uiState 登录页状态（手机号 / 验证码 / 冷却 / 进行中标志）。
 * @param viewModel 交互入口（发送验证码 / 登录）。
 */
@Composable
fun SmsCodeSection(
    uiState: LoginUiState,
    viewModel: LoginViewModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 风控提示（iOS smsSection 顶部警告条）。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = stringResource(R.string.login_sms_risk_notice),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // 手机号（+86 前缀）。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "+86",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(20.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            OutlinedTextField(
                value = uiState.phone,
                onValueChange = viewModel::setPhone,
                placeholder = { Text(stringResource(R.string.login_sms_phone)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Phone,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 验证码 + 获取按钮（冷却倒计时）。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = uiState.code,
                onValueChange = viewModel::setCode,
                placeholder = { Text(stringResource(R.string.login_sms_code)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = viewModel::sendSmsCode,
                enabled = uiState.canSendSmsCode,
            ) {
                when {
                    uiState.smsSending -> CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    uiState.smsCooldown > 0 -> Text("${uiState.smsCooldown}s")
                    else -> Text(stringResource(R.string.login_sms_send))
                }
            }
        }

        // 通道提示（验证码已发送 / 风控错误）。
        uiState.smsMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 登录按钮（品牌渐变胶囊，iOS Capsule + accentGradient）。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(
                    if (uiState.canLoginWithSms) BrandGradient
                    else androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                        ),
                    ),
                )
                .clickable(enabled = uiState.canLoginWithSms) { viewModel.loginWithSms() },
            contentAlignment = Alignment.Center,
        ) {
            if (uiState.smsLoggingIn) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                )
            } else {
                Text(
                    text = stringResource(R.string.login_sms_login),
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                )
            }
        }
    }
}
