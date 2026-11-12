// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 短信验证码登录分区——视觉 1:1 复刻网易云官方登录页(逆向规格:
// /h/apk-work/netease/decoded/res/layout/amx.xml + rz.xml + drawable/b1b.xml):
// 输入容器 = 56dp 高、圆角 12dp、填充 #0F283248(8% 深蓝黑);分线 1dp×16dp
// #1A283248;正文 #FF283248 / 次级 #99283248 / hint #4D283248 / 链接 #FF5975B2;
// 登录按钮 46dp 高、官方红 #EC4141。

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rc.harken.design.theme.BrandGradient
import com.rc.harken.feature.login.LoginUiState
import com.rc.harken.feature.login.LoginViewModel
import com.rc.harken.feature.login.R

// 官方登录页色板(逆向自官方 APK 登录布局,见文件头注释)。
private val OfficialFill = Color(0x0F283248)
private val OfficialSplit = Color(0x1A283248)
private val OfficialText = Color(0xFF283248)
private val OfficialTextSecondary = Color(0x99283248)
private val OfficialHint = Color(0x4D283248)
private val OfficialLink = Color(0xFF5975B2)
private val OfficialRed = Color(0xFFEC4141)

/** 「获取验证码」按钮槽宽:手机号 / 验证码两行输入框共用同一右边界。 */
private val SEND_SLOT_DP = 120.dp

/**
 * 官方样式输入行容器:56dp 高、圆角 12dp、填充 #0F283248(官方 b1b drawable)。
 */
@Composable
private fun OfficialInputRow(
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(MaterialTheme.shapes.large)
            .background(OfficialFill),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/** 官方样式输入框(透明底、17sp #FF283248、cursor 品牌红)。 */
@Composable
private fun OfficialTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholderText: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(fontSize = 17.sp, color = OfficialText),
        cursorBrush = SolidColor(OfficialRed),
        keyboardOptions = keyboardOptions,
        modifier = modifier,
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text(
                        text = placeholderText,
                        fontSize = 17.sp,
                        color = OfficialHint,
                    )
                }
                inner()
            }
        },
    )
}

/**
 * 短信验证码登录表单(官方 amx/rz 规格)。
 *
 * @param uiState 登录页状态(手机号 / 验证码 / 冷却 / 进行中标志)。
 * @param viewModel 交互入口(发送验证码 / 登录)。
 */
@Composable
fun SmsCodeSection(
    uiState: LoginUiState,
    viewModel: LoginViewModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // 风控提示(官方:12sp 链接色辅助文字,无底框)。
        Text(
            text = stringResource(R.string.login_sms_risk_notice),
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = OfficialLink,
            modifier = Modifier.fillMaxWidth(),
        )

        // 手机号行:+86 区号(官方 areaCode 20sp #99283248)+ 分线 + 输入框。
        OfficialInputRow {
            Text(
                text = "+86",
                fontSize = 20.sp,
                color = OfficialTextSecondary,
                modifier = Modifier.padding(start = 16.dp),
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 10.dp)
                    .width(1.dp)
                    .height(16.dp)
                    .background(OfficialSplit),
            )
            OfficialTextField(
                value = uiState.phone,
                onValueChange = viewModel::setPhone,
                placeholderText = stringResource(R.string.login_sms_phone),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Phone,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 16.dp),
            )
        }

        // 验证码行:输入框 + 分线 + 获取验证码(官方 functionArea 70dp 槽)。
        OfficialInputRow {
            OfficialTextField(
                value = uiState.code,
                onValueChange = viewModel::setCode,
                placeholderText = stringResource(R.string.login_sms_code),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp),
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(16.dp)
                    .background(OfficialSplit),
            )
            Box(
                modifier = Modifier
                    .width(SEND_SLOT_DP)
                    .clickable { if (uiState.canSendSmsCode) viewModel.sendSmsCode() },
                contentAlignment = Alignment.Center,
            ) {
                when {
                    uiState.smsSending -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = OfficialLink,
                    )
                    uiState.smsCooldown > 0 -> Text(
                        text = "${uiState.smsCooldown}s",
                        fontSize = 14.sp,
                        color = OfficialTextSecondary,
                    )
                    else -> Text(
                        text = stringResource(R.string.login_sms_send),
                        fontSize = 14.sp,
                        color = OfficialLink,
                    )
                }
            }
        }

        // 通道提示(验证码已发送 / 风控错误)。
        uiState.smsMessage?.let { message ->
            Text(
                text = message,
                fontSize = 12.sp,
                color = OfficialLink,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 登录按钮(官方 NewLoginCommonBtn:46dp 高、圆角、红底白字 17sp)。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(CircleShape)
                .background(
                    if (uiState.canLoginWithSms) {
                        SolidColor(OfficialRed)
                    } else {
                        Brush.linearGradient(listOf(OfficialFill, OfficialFill))
                    },
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
                    fontSize = 17.sp,
                    color = Color.White,
                )
            }
        }
    }
}
