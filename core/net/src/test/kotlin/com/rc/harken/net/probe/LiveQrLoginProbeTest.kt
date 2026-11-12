// ⚠️ 真实网络探测 —— **不属于 CI**，默认自动跳过。
//
// 触发方式（Git Bash，仓库根）：
//   1) touch core/net/live-qr-probe.enabled
//   2) HARKEN_LIVE_QR_PROBE=1 GRADLE_USER_HOME=/d/gradle ./gradlew :core:net:test \
//        --tests "com.rc.harken.net.probe.LiveQrLoginProbeTest"
//   3) 看 core/net/build/live-qr-probe.txt（Gradle 默认吞 stdout，故落盘）
//   4) rm core/net/live-qr-probe.enabled
//
// 门控用「标记文件」而非环境变量：Gradle daemon 复用时测试 worker 未必拿到
// 新设置的环境变量，标记文件是确定性的。其它人跑全量测试时该文件不存在 → 跳过。

package com.rc.harken.net.probe

import com.rc.harken.net.api.NeteaseApi
import com.rc.harken.net.client.NeteaseClient
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume
import org.junit.Before
import org.junit.Test

class LiveQrLoginProbeTest {

    private val log = StringBuilder()

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    @Before
    fun requireOptIn() {
        Assume.assumeTrue(
            "需要真实网络：先创建 core/net/live-qr-probe.enabled 再跑 --tests 该探测",
            File("live-qr-probe.enabled").exists() || File("core/net/live-qr-probe.enabled").exists(),
        )
    }

    private fun record(section: String, body: String) {
        log.appendLine("===== $section =====").appendLine(body.trim().ifEmpty { "<空响应体>" }).appendLine()
    }

    private suspend fun call(section: String, block: suspend () -> String): String? =
        try {
            val body = block()
            record(section, body)
            body
        } catch (e: Exception) {
            record(section, "EXCEPTION: ${e::class.java.name}: ${e.message}")
            null
        }

    private fun get(url: String, maxLen: Int = Int.MAX_VALUE): String =
        http.newCall(
            Request.Builder().url(url)
                .header("User-Agent", NeteaseClient.USER_AGENT)
                .header("Referer", NeteaseClient.REFERER)
                .get()
                .build(),
        ).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            "HTTP ${resp.code} ${resp.message}\n" + body.take(maxLen)
        }

    private fun postForm(url: String, fields: Map<String, String>): String {
        val form = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        return http.newCall(
            Request.Builder().url(url)
                .header("User-Agent", NeteaseClient.USER_AGENT)
                .header("Referer", NeteaseClient.REFERER)
                .post(form)
                .build(),
        ).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            "HTTP ${resp.code} ${resp.message}\n$body"
        }
    }

    private fun unikeyOf(raw: String?): String? =
        raw?.let { Regex("\"unikey\"\\s*:\\s*\"([^\"]*)\"").find(it)?.groupValues?.get(1) }
            ?.takeIf { it.isNotBlank() }

    @Test
    fun `probe qr login endpoints`() = runBlocking {
        val client = NeteaseClient()

        // ── 1) 取 unikey：我们当前的 weapi 路径 vs 参考实现的 /api 前缀路径 vs 明文 api ──
        val rawA = call("A. weapi POST /weapi/login/qrcode/unikey  {type:1}（当前实现路径）") {
            client.weapi("/login/qrcode/unikey", mapOf("type" to 1))
        }
        val rawB = call("B. weapi POST /weapi/api/login/qrcode/unikey  {type:1}（带 /api 前缀）") {
            client.weapi("/api/login/qrcode/unikey", mapOf("type" to 1))
        }
        val rawC = call("C. 明文 GET /api/login/qrcode/unikey?type=1") {
            get("https://music.163.com/api/login/qrcode/unikey?type=1")
        }
        val rawD = call("D. 明文 POST form /api/login/qrcode/unikey  type=1") {
            postForm("https://music.163.com/api/login/qrcode/unikey", mapOf("type" to "1"))
        }

        val unikey = unikeyOf(rawB) ?: unikeyOf(rawA) ?: unikeyOf(rawC) ?: unikeyOf(rawD)
        record("UNIKEY", unikey ?: "<未取到>")
        record(
            "UNIKEY 形态检查",
            "len=${unikey?.length} matchesUuid=${unikey != null && Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(unikey)}",
        )
        record("二维码内容（QR URL）", "https://music.163.com/login?codekey=${unikey ?: "<空>"}")

        // ── 2) 轮询 unikey：三条通道对照（未扫码时预期 code=801）──
        val key = unikey ?: "00000000-0000-0000-0000-000000000000"
        call("E. weapi POST /weapi/login/qrcode/client/login  {key,type:1}（当前实现路径）") {
            client.weapi("/login/qrcode/client/login", mapOf("key" to key, "type" to 1))
        }
        call("F. weapi POST /weapi/api/login/qrcode/client/login  {key,type:1}（带 /api 前缀）") {
            client.weapi("/api/login/qrcode/client/login", mapOf("key" to key, "type" to 1))
        }
        call("G. 明文 GET /api/login/qrcode/client/login?key=...&type=1") {
            get("https://music.163.com/api/login/qrcode/client/login?key=$key&type=1")
        }

        // ── 3) 应用真实调用链：NeteaseApi.qrKey() / qrCheck() ──
        val api = NeteaseApi(client)
        val appKey = call("H. NeteaseApi.qrKey()  ← 应用实际走的就是这条") { api.qrKey() }
        if (appKey != null) {
            record("H2. 应用二维码 URL", "https://music.163.com/login?codekey=$appKey")
            call("I. NeteaseApi.qrCheck(<appKey>)  ← 应用实际轮询") {
                api.qrCheck(appKey).toString()
            }
        }

        // ── 4) 扫码目标页可达性（只看状态码 + 前 200 字符）──
        if (unikey != null) {
            call("J. GET 扫码目标页 https://music.163.com/login?codekey=<unikey>（截断 200 字符）") {
                get("https://music.163.com/login?codekey=$unikey", maxLen = 200)
            }
        }

        File("build/live-qr-probe.txt").writeText(log.toString())
        println(log)
    }

    /** 轮询频率 / 无效 key / 手机端 server-login 的错误签名对照。 */
    @Test
    fun `probe polling rate and invalid key signatures`() = runBlocking {
        val client = NeteaseClient()
        val raw = client.weapi("/login/qrcode/unikey", mapOf("type" to 1))
        val key = unikeyOf(raw).orEmpty()
        record("K0. 新 key", "$raw\nkey=$key")

        // K1. 模拟 App 的 1.5s 轮询节奏，连续 6 次，看是否被风控。
        repeat(6) { i ->
            val body = try {
                client.weapi("/login/qrcode/client/login", mapOf("key" to key, "type" to 1))
            } catch (e: Exception) {
                "EXCEPTION: ${e::class.java.name}: ${e.message}"
            }
            record("K1.${i + 1} 第 ${i + 1} 次轮询（间隔 1.5s）", body)
            if (i < 5) kotlinx.coroutines.delay(1500)
        }

        // K2. 无效 key（随机 UUID）：拿到的是哪个码？（App 若拿到非 801/802/803 会静默卡死）
        val bogus = "11111111-2222-3333-4444-555555555555"
        call("K2. 无效 key 轮询") {
            client.weapi("/login/qrcode/client/login", mapOf("key" to bogus, "type" to 1))
        }

        // K3. 空 key（模拟 qrKey 失败但 UI 仍渲染空二维码的情况）
        call("K3. 空 key 轮询") {
            client.weapi("/login/qrcode/client/login", mapOf("key" to "", "type" to 1))
        }

        // K4. 模拟手机端「扫码确认」调用（未带登录态，预期报需要登录/参数缺失）。
        call("K4. 模拟手机端 login/qrcode/server/login {key,type:1}") {
            client.weapi("/login/qrcode/server/login", mapOf("key" to key, "type" to 1))
        }

        File("build/live-qr-probe-2.txt").writeText(log.toString())
        println(log)
    }
}
