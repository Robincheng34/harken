// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 登录 ViewModel（T17 `LoginViewModel.kt`：扫码 + 短信验证码双通道）。
//
// 扫码：获取二维码 key → 构造登录 URL → 轮询 qrCheck（800/801/802/803）。
// 短信：sendSMSCode（60s 冷却）→ loginCellphone → userAccount 拉取档案。
// 轮询间隔 1.5s；成功/过期即终止。无 Android 依赖（纯 Kotlin + 协程），可纯
// JVM 单测（见 `LoginViewModelTest`）。

package com.rc.harken.feature.login

import com.rc.harken.net.api.NeteaseApi
import com.rc.harken.net.api.NeteaseApiError
import com.rc.harken.net.api.userMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 登录状态机：扫码轮询（800/801/802/803）+ 短信验证码。 */
open class LoginViewModel(
    protected val api: NeteaseApi,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val _uiState: MutableStateFlow<LoginUiState> = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    /** 轮询间隔（毫秒）。可被测试覆写以加速。 */
    protected open val pollIntervalMillis: Long = 1500L

    /** 短信冷却 tick 间隔（毫秒）。可被测试覆写以加速。 */
    protected open val cooldownTickMillis: Long = 1000L

    private var pollJob: Job? = null
    private var cooldownJob: Job? = null
    private var currentUnikey: String? = null

    // MARK: - 模式与输入

    /** 切换登录方式（iOS `mode` picker）。 */
    fun switchMode(mode: LoginMode) {
        if (_uiState.value.mode == mode) return
        _uiState.update { it.copy(mode = mode, errorMessage = null, smsMessage = null) }
        if (mode == LoginMode.QR) {
            cooldownJob?.cancel()
            cooldownJob = null
            val status = _uiState.value.status
            if (status == LoginStatus.Idle || status == LoginStatus.Error) {
                startQrLogin()
            } else if (pollJob?.isActive != true) {
                // 原 bug：切到 SMS 会 release() 取消 pollJob，切回 QR 时仅在
                // Idle/Error 态重启轮询——Waiting/Scanned 态切回则轮询永久死亡
                // 且刷新按钮不显示。此处复用现有 unikey 恢复轮询（无 key 则重开）。
                val key = currentUnikey
                if (key != null) pollJob = scope.launch { poll(key) } else startQrLogin()
            }
        } else {
            release()
        }
    }

    fun setPhone(phone: String) {
        _uiState.update { it.copy(phone = phone.filter { c -> c.isDigit() }.take(20)) }
    }

    fun setCode(code: String) {
        _uiState.update { it.copy(code = code.filter { c -> c.isDigit() }.take(8)) }
    }

    // MARK: - 扫码登录

    /** 开始/重开扫码登录。 */
    fun startQrLogin() {
        pollJob?.cancel()
        currentUnikey = null
        pollJob = scope.launch {
            _uiState.update { it.copy(status = LoginStatus.Loading, errorMessage = null) }
            val unikey = runCatching { api.qrKey() }.getOrElse { e ->
                _uiState.update {
                    it.copy(status = LoginStatus.Error, errorMessage = mapError(e))
                }
                return@launch
            }
            currentUnikey = unikey
            val url = api.qrLoginURL(unikey)
            _uiState.update {
                it.copy(status = LoginStatus.Waiting, unikey = unikey, qrUrl = url)
            }
            poll(unikey)
        }
    }

    private suspend fun poll(unikey: String) {
        while (scope.isActive) {
            delay(pollIntervalMillis)
            val result = runCatching { api.qrCheck(unikey) }.getOrNull() ?: continue
            when (result.code) {
                800 -> {
                    _uiState.update { it.copy(status = LoginStatus.Expired) }
                    return
                }
                801 -> _uiState.update { it.copy(status = LoginStatus.Waiting) }
                802 -> _uiState.update { it.copy(status = LoginStatus.Scanned) }
                803 -> {
                    _uiState.update {
                        it.copy(
                            status = LoginStatus.Confirmed,
                            nickname = result.nickname,
                            avatarUrl = result.avatarUrl,
                        )
                    }
                    onConfirmed(result.nickname, result.avatarUrl)
                    return
                }
                else -> { /* 其它码忽略 */ }
            }
        }
    }

    /** 登录成功钩子（子类/宿主可覆写以写入登录态）。 */
    protected open fun onConfirmed(nickname: String?, avatarUrl: String?) {}

    /** 生命周期 onResume：若在等待态且轮询已停（如后台回收），重开。 */
    fun onResume() {
        if (_uiState.value.status == LoginStatus.Waiting && pollJob?.isActive != true) {
            val key = currentUnikey
            if (key != null) { pollJob = scope.launch { poll(key) } }
            else startQrLogin()
        }
    }

    fun release() {
        pollJob?.cancel()
        pollJob = null
    }

    // MARK: - 短信登录

    /** 发送验证码；成功后进入 60s 冷却（iOS `sendCode`）。 */
    fun sendSmsCode() {
        val state = _uiState.value
        if (!state.canSendSmsCode) return
        _uiState.update { it.copy(smsSending = true, smsMessage = null) }
        scope.launch {
            val failure = runCatching { api.sendSMSCode(state.phone) }.exceptionOrNull()
            if (failure == null) {
                _uiState.update {
                    it.copy(smsSending = false, smsMessage = "验证码已发送")
                }
                startCooldown(SMS_COOLDOWN_SECONDS)
            } else {
                _uiState.update {
                    it.copy(smsSending = false, smsMessage = mapError(failure))
                }
            }
        }
    }

    /** 用验证码登录；成功后拉取用户档案并走 [onConfirmed]（iOS `loginWithCode`）。 */
    fun loginWithSms() {
        val state = _uiState.value
        if (!state.canLoginWithSms) return
        _uiState.update { it.copy(smsLoggingIn = true, smsMessage = null) }
        scope.launch {
            val failure = runCatching { api.loginCellphone(state.phone, state.code) }.exceptionOrNull()
            if (failure != null) {
                _uiState.update {
                    it.copy(smsLoggingIn = false, smsMessage = mapError(failure))
                }
                return@launch
            }
            val profile = runCatching { api.userAccount() }.getOrNull()
            _uiState.update {
                it.copy(
                    smsLoggingIn = false,
                    status = LoginStatus.Confirmed,
                    nickname = profile?.nickname,
                    avatarUrl = profile?.avatarUrl,
                )
            }
            onConfirmed(profile?.nickname, profile?.avatarUrl)
        }
    }

    private fun startCooldown(seconds: Int) {
        cooldownJob?.cancel()
        _uiState.update { it.copy(smsCooldown = seconds) }
        cooldownJob = scope.launch {
            while (_uiState.value.smsCooldown > 0 && isActive) {
                delay(cooldownTickMillis)
                _uiState.update { it.copy(smsCooldown = (it.smsCooldown - 1).coerceAtLeast(0)) }
            }
        }
    }

    private fun mapError(e: Throwable): String = when (e) {
        is NeteaseApiError -> e.userMessage()
        else -> e.message ?: "登录失败"
    }

    private companion object {
        const val SMS_COOLDOWN_SECONDS = 60
    }
}
