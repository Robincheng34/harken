// Ported from the upstream LGPL-3.0 project (see NOTICE)
//
// 登录状态机单测（T17 `LoginViewModelTest.kt`，纯 JVM）。
//
// 通过覆写 NeteaseApi 的 qrKey/qrCheck/sendSMSCode/loginCellphone/userAccount
// 注入确定性序列，验证 800/801/802/803 扫码流转与短信发送/登录/冷却路径。

package com.rc.harken.feature.login

import com.rc.harken.net.api.NeteaseApi
import com.rc.harken.net.client.InMemoryCookieStore
import com.rc.harken.net.client.NeteaseClient
import com.rc.harken.net.model.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    private fun fakeApi(sequence: List<Int>): NeteaseApi =
        object : NeteaseApi(NeteaseClient(InMemoryCookieStore())) {
            override suspend fun qrKey(): String = "unikey-test"
            private var index = 0
            override suspend fun qrCheck(unikey: String): NeteaseApi.QRCheckResult {
                val code = sequence.getOrElse(index) { 801 }
                index += 1
                return when (code) {
                    803 -> NeteaseApi.QRCheckResult(803, nickname = "tester", avatarUrl = "http://a")
                    else -> NeteaseApi.QRCheckResult(code)
                }
            }
        }

    /** 短信假 API：记录调用，可配置失败。 */
    private class FakeSmsApi(
        private val sendError: Throwable? = null,
        private val loginError: Throwable? = null,
    ) : NeteaseApi(NeteaseClient(InMemoryCookieStore())) {
        var sentPhone: String? = null
        var loginArgs: Pair<String, String>? = null

        override suspend fun sendSMSCode(phone: String, countryCode: String) {
            sentPhone = phone
            sendError?.let { throw it }
        }

        override suspend fun loginCellphone(phone: String, captcha: String, countryCode: String) {
            loginArgs = phone to captcha
            loginError?.let { throw it }
        }

        override suspend fun userAccount(): UserProfile =
            UserProfile(userId = 42, nickname = "sms-user", avatarUrl = "http://avatar")
    }

    private fun smsViewModel(
        api: NeteaseApi,
        dispatcher: TestDispatcher,
    ): LoginViewModel =
        object : LoginViewModel(api, CoroutineScope(dispatcher)) {
            override val pollIntervalMillis: Long = 0L
            override val cooldownTickMillis: Long = 1000L
        }

    @Test
    fun `qr login transitions 801 802 803 and confirms`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val vm = object : LoginViewModel(fakeApi(listOf(801, 802, 803)), CoroutineScope(dispatcher)) {
                override val pollIntervalMillis: Long = 0L
            }
            vm.startQrLogin()
            advanceUntilIdle()
            assertEquals(LoginStatus.Confirmed, vm.uiState.value.status)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `qr login expires on 800`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val vm = object : LoginViewModel(fakeApi(listOf(801, 800)), CoroutineScope(dispatcher)) {
                override val pollIntervalMillis: Long = 0L
            }
            vm.startQrLogin()
            advanceUntilIdle()
            assertEquals(LoginStatus.Expired, vm.uiState.value.status)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `qr login surfaces error when qrKey fails`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val api = object : NeteaseApi(NeteaseClient(InMemoryCookieStore())) {
                override suspend fun qrKey(): String = throw RuntimeException("network")
            }
            val vm = object : LoginViewModel(api, CoroutineScope(dispatcher)) {
                override val pollIntervalMillis: Long = 0L
            }
            vm.startQrLogin()
            advanceUntilIdle()
            assertEquals(LoginStatus.Error, vm.uiState.value.status)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `sms send code succeeds and starts cooldown`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val api = FakeSmsApi()
            val vm = smsViewModel(api, dispatcher)
            vm.switchMode(LoginMode.SMS)
            vm.setPhone("13800000000")
            vm.sendSmsCode()
            runCurrent()

            assertEquals("13800000000", api.sentPhone)
            val state = vm.uiState.value
            assertFalse(state.smsSending)
            assertEquals("验证码已发送", state.smsMessage)
            assertEquals(60, state.smsCooldown)

            // 冷却期间禁止再次发送。
            assertFalse(state.canSendSmsCode)
            // 冷却 60s，每秒递减一次；推进 2s 应完成两次递减（60 → 58）。
            // 测试调度器对区间末端（恰好 2000ms 处那次 tick）为开区间，故多推进
            // 1ms 越过边界以触发第二次递减。
            advanceTimeBy(2_001)
            runCurrent()
            assertEquals(58, vm.uiState.value.smsCooldown)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `sms send code surfaces error`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val api = FakeSmsApi(sendError = RuntimeException("captcha blocked"))
            val vm = smsViewModel(api, dispatcher)
            vm.switchMode(LoginMode.SMS)
            vm.setPhone("13800000000")
            vm.sendSmsCode()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertFalse(state.smsSending)
            assertEquals(0, state.smsCooldown)
            assertTrue(state.smsMessage?.contains("captcha blocked") == true)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `sms login confirms and loads profile`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val api = FakeSmsApi()
            val vm = smsViewModel(api, dispatcher)
            vm.switchMode(LoginMode.SMS)
            vm.setPhone("13800000000")
            vm.setCode("123456")
            vm.loginWithSms()
            advanceUntilIdle()

            assertEquals("13800000000" to "123456", api.loginArgs)
            val state = vm.uiState.value
            assertEquals(LoginStatus.Confirmed, state.status)
            assertEquals("sms-user", state.nickname)
            assertFalse(state.smsLoggingIn)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `sms login surfaces error and stays on form`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val api = FakeSmsApi(loginError = RuntimeException("code wrong"))
            val vm = smsViewModel(api, dispatcher)
            vm.switchMode(LoginMode.SMS)
            vm.setPhone("13800000000")
            vm.setCode("123456")
            vm.loginWithSms()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(LoginStatus.Idle, state.status)
            assertFalse(state.smsLoggingIn)
            assertTrue(state.smsMessage?.contains("code wrong") == true)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
