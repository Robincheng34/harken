// QA-authored QR-login state-machine boundary tests.

package com.rc.harken.feature.login

import com.rc.harken.net.api.NeteaseApi
import com.rc.harken.net.api.NeteaseApiError
import com.rc.harken.net.client.InMemoryCookieStore
import com.rc.harken.net.client.NeteaseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unknown codes, repeat calls and API-level failures during QR polling. */
@OptIn(ExperimentalCoroutinesApi::class)
class QaQrLoginBoundaryTest {

    /**
     * API whose qrKey fails and whose qrCheck replays a scripted sequence.
     *
     * Once the script is exhausted the poll returns 800 (expired), a terminal
     * code — otherwise the `while (isActive)` poll loop would never return and
     * `advanceUntilIdle()` would spin forever.
     */
    private class ScriptedApi(
        private val codes: List<Int> = emptyList(),
        private val qrKeyError: Throwable? = null,
        private val checkErrors: List<Throwable> = emptyList(),
    ) : NeteaseApi(NeteaseClient(InMemoryCookieStore())) {

        var keyCalls = 0
        var checkCalls = 0
        val seenCodes = mutableListOf<Int>()

        override suspend fun qrKey(): String {
            keyCalls += 1
            qrKeyError?.let { throw it }
            return "unikey-$keyCalls"
        }

        override suspend fun qrCheck(unikey: String): NeteaseApi.QRCheckResult {
            val index = checkCalls
            checkCalls += 1
            checkErrors.getOrNull(index)?.let { throw it }
            val code = codes.getOrElse(index) { 800 }
            seenCodes.add(code)
            return if (code == 803) {
                NeteaseApi.QRCheckResult(803, nickname = "tester", avatarUrl = "http://a")
            } else {
                NeteaseApi.QRCheckResult(code)
            }
        }
    }

    private fun viewModel(
        api: NeteaseApi,
        dispatcher: TestDispatcher,
    ): LoginViewModel = object : LoginViewModel(api, CoroutineScope(dispatcher)) {
        override val pollIntervalMillis: Long = 100L
        override val cooldownTickMillis: Long = 1000L
    }

    // ---- unknown / unexpected codes --------------------------------------

    /** A code outside 800/801/802/803 must be ignored, not treated as success. */
    @Test
    fun `an unknown code is ignored and polling continues`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        // 999/777 are unknown; 801 keeps waiting; 803 finally confirms.
        val api = ScriptedApi(codes = listOf(999, 777, 801, 803))
        val model = viewModel(api, dispatcher)

        model.startQrLogin()
        advanceUntilIdle()

        // The unknown codes must have been consumed and skipped, not treated
        // as a confirmation: the 801 in between kept it in Waiting.
        assertEquals(
            "the unknown codes must not short-circuit the poll",
            LoginStatus.Confirmed,
            model.uiState.value.status,
        )
        assertEquals("801 before 803", 801, api.seenCodes[2])
        assertEquals("all four scripted codes consumed", 4, api.checkCalls)
    }

    /** A negative or zero code must also be ignored. */
    @Test
    fun `a non-positive code is ignored`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        // 802 is the last scripted code; the 800 fallback then ends the poll.
        val api = ScriptedApi(codes = listOf(-1, 0, 802))
        val model = viewModel(api, dispatcher)

        model.startQrLogin()
        advanceUntilIdle()

        assertEquals(LoginStatus.Expired, model.uiState.value.status)
        assertEquals("all three scripted codes must be consumed", 4, api.checkCalls)
    }

    /** A 803 with no nickname must still confirm. */
    @Test
    fun `a 803 without a nickname still confirms`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val api = object : NeteaseApi(NeteaseClient(InMemoryCookieStore())) {
            override suspend fun qrKey(): String = "k"
            override suspend fun qrCheck(unikey: String) =
                NeteaseApi.QRCheckResult(803, nickname = null, avatarUrl = null)
        }
        val model = viewModel(api, dispatcher)

        model.startQrLogin()
        advanceUntilIdle()

        assertEquals(LoginStatus.Confirmed, model.uiState.value.status)
        assertNull(model.uiState.value.nickname)
    }

    // ---- repeat invocations ----------------------------------------------

    /** A second startQrLogin must not leave two pollers running. */
    @Test
    fun `repeated startQrLogin does not double poll`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val api = ScriptedApi(codes = listOf(801))
        val model = viewModel(api, dispatcher)

        // runCurrent() between restarts so each launch actually reaches
        // qrKey() before the next one cancels it; otherwise the earlier jobs
        // are cancelled while still queued and never execute at all.
        model.startQrLogin()
        runCurrent()
        model.startQrLogin()
        runCurrent()
        model.startQrLogin()
        advanceUntilIdle()

        assertEquals(
            "each start must fetch exactly one key, never stack pollers",
            3,
            api.keyCalls,
        )
        assertEquals("the latest unikey must win", "unikey-3", model.uiState.value.unikey)
        // A stacked poller would keep its own delay loop alive and drive
        // qrCheck more than once past the surviving loop's single pass.
        assertTrue(
            "only the surviving poller may drive qrCheck, got ${api.checkCalls}",
            api.checkCalls < 3,
        )
    }

    /** startQrLogin resets a previous terminal state. */
    @Test
    fun `startQrLogin recovers from the expired state`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val api = ScriptedApi(codes = listOf(800))
        val model = viewModel(api, dispatcher)

        model.startQrLogin()
        advanceUntilIdle()
        assertEquals(LoginStatus.Expired, model.uiState.value.status)

        model.startQrLogin()
        advanceUntilIdle()
        assertEquals(LoginStatus.Expired, model.uiState.value.status)
        assertEquals("a restart must refetch the key", 2, api.keyCalls)
    }

    /** startQrLogin clears a stale error message. */
    @Test
    fun `startQrLogin clears a previous error message`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val api = ScriptedApi(qrKeyError = NeteaseApiError.NeedLogin)
        val model = viewModel(api, dispatcher)

        model.startQrLogin()
        advanceUntilIdle()
        assertNotNull(model.uiState.value.errorMessage)

        val healthy = ScriptedApi(codes = listOf(801))
        val model2 = viewModel(healthy, dispatcher)
        model2.startQrLogin()
        advanceUntilIdle()
        assertNull(model2.uiState.value.errorMessage)
    }

    // ---- qrKey failure modes --------------------------------------------

    @Test
    fun `a NeedLogin business error surfaces a message`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val api = ScriptedApi(qrKeyError = NeteaseApiError.NeedLogin)
        val model = viewModel(api, dispatcher)

        model.startQrLogin()
        advanceUntilIdle()

        assertEquals(LoginStatus.Error, model.uiState.value.status)
        assertNotNull(model.uiState.value.errorMessage)
    }

    @Test
    fun `an HTTP error from qrKey surfaces a message`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val api = ScriptedApi(qrKeyError = NeteaseApiError.Http(503))
        val model = viewModel(api, dispatcher)

        model.startQrLogin()
        advanceUntilIdle()

        assertEquals(LoginStatus.Error, model.uiState.value.status)
        assertNotNull(model.uiState.value.errorMessage)
    }

    /** A thrown exception during polling must not kill the state machine. */
    @Test
    fun `a transient polling error is retried`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val api = ScriptedApi(
            codes = listOf(801, 801, 803),
            checkErrors = listOf(RuntimeException("network blip")),
        )
        val model = viewModel(api, dispatcher)

        model.startQrLogin()
        advanceUntilIdle()

        assertEquals("a failed poll must be retried", 3, api.checkCalls)
        assertEquals(LoginStatus.Confirmed, model.uiState.value.status)
    }

    // ---- release / resume -----------------------------------------------

    /** After release, polling stops. */
    @Test
    fun `release stops polling`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val api = ScriptedApi(codes = listOf(801, 801, 801, 801))
        val model = viewModel(api, dispatcher)

        model.startQrLogin()
        runCurrent()
        model.release()
        val callsAtRelease = api.checkCalls
        advanceUntilIdle()

        assertEquals("no further polls after release", callsAtRelease, api.checkCalls)
    }
    @Test
    fun `onResume resumes polling with the same unikey`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val api = ScriptedApi(codes = listOf(801, 802, 803))
        val model = viewModel(api, dispatcher)

        model.startQrLogin()
        runCurrent()
        val unikey = model.uiState.value.unikey
        model.release()
        runCurrent()

        model.onResume()
        advanceUntilIdle()

        assertEquals("the unikey must be reused", unikey, model.uiState.value.unikey)
        assertEquals("no extra qrKey fetch", 1, api.keyCalls)
        assertEquals(LoginStatus.Confirmed, model.uiState.value.status)
    }

    /** Switching to SMS mode must stop QR polling. */
    @Test
    fun `switching to sms stops qr polling`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val api = ScriptedApi(codes = listOf(801, 801, 801, 801))
        val model = viewModel(api, dispatcher)

        model.startQrLogin()
        runCurrent()
        model.switchMode(LoginMode.SMS)
        val calls = api.checkCalls
        advanceUntilIdle()

        assertEquals(calls, api.checkCalls)
        assertEquals(LoginMode.SMS, model.uiState.value.mode)
    }

    /** The QR url must be derived from the fetched unikey. */
    @Test
    fun `the qr url contains the unikey`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val api = ScriptedApi(codes = listOf(801))
        val model = viewModel(api, dispatcher)

        model.startQrLogin()
        advanceUntilIdle()

        val url = model.uiState.value.qrUrl
        assertNotNull(url)
        assertTrue("url should embed the unikey: $url", url!!.contains("unikey-1"))
    }

    // ---- input sanitising ------------------------------------------------

    @Test
    fun `phone input keeps digits only`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val model = viewModel(ScriptedApi(), dispatcher)

        model.setPhone("138-0013-8000")
        assertEquals("13800138000", model.uiState.value.phone)
    }

    @Test
    fun `code input keeps digits only`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val model = viewModel(ScriptedApi(), dispatcher)

        model.setCode("12ab34")
        assertEquals("1234", model.uiState.value.code)
    }

    @Test
    fun `an empty code blocks sms login`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val model = viewModel(ScriptedApi(), dispatcher)

        model.setPhone("13800138000")
        assertFalse(model.uiState.value.canLoginWithSms)

        model.loginWithSms()
        advanceUntilIdle()
        assertFalse(model.uiState.value.smsLoggingIn)
    }

    @Test
    fun `a short phone number blocks sending an sms`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val model = viewModel(ScriptedApi(), dispatcher)

        model.setPhone("138")
        assertFalse(model.uiState.value.canSendSmsCode)
    }
}