// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken.net.crypto

/**
 * NetEase Cloud Music request encryption (weapi / eapi).
 *
 * Ported 1:1 from `NeteaseCrypto.swift`; parameters are byte-identical to iOS.
 *
 * weapi: two rounds of AES-128-CBC over the JSON payload — first with a preset
 * key, then with a client-chosen secret key. The secret key is normally
 * RSA-encrypted per request; since the key is chosen by us, we ship a fixed
 * key with its RSA ciphertext precomputed (verified against the public
 * NetEase weapi RSA key: `encSecKey = RSA(reverse(secretKey))`), avoiding a
 * BigInt implementation.
 *
 * eapi: AES-128-ECB over "url + payload + md5 digest" with a fixed key,
 * uppercase hex output.
 */
object NeteaseCrypto {

    private const val WEAPI_PRESET_KEY = "0CoJUm6Qyw8W8jud"
    private const val WEAPI_IV = "0102030405060708"

    // weapi 客户端自选 secretKey，与下面的 WEAPI_ENC_SEC_KEY 配对：
    // encSecKey = RSA(reverse(secretKey))（裸 RSA，exp 010001，模数 N 见
    // workbuddy 2026-10-23 记录，末段 …3ece0462db0a22b8e7）。
    // 改 secretKey 必须同步重算 encSecKey，否则所有 /weapi/ 请求服务端解密失败。
    // 2026-10-23 由旧 secretKey 改为 harken2026abcDEF（品牌迁移收尾）。
    //
    // ⚠️ 重算自校验（2026-10-24）：用旧 secretKey `kumone2026abcDEF` 跑同一模型，
    // 必须精确复现旧常量 38cef2ef…62cdc9。注意两点历史教训：
    // ① 网上流传的 1024-bit 截断写法与本项目验证用的 N 末段不同，用错 N 复算
    //    出「不匹配」会被误判成「模型错误」（2026-10-24 评审误报根因）；
    // ② 服务端把 encSecKey 当十六进制整数解析，前导零不影响数值——但仓库约定
    //    恒写 256 hex 位（`[0-9a-f]{256}` 测试断言），重算后须 PadLeft 到 256。
    private const val WEAPI_SECRET_KEY = "harken2026abcDEF"

    /** Precomputed `RSA(reverse("harken2026abcDEF"))` under the public weapi key. */
    private const val WEAPI_ENC_SEC_KEY =
        "d81f45ea484c193575512ecbef2799713bc7db00c779f5d4f2d85f0a3cf047de" +
        "cb33dbeeef38171e5d4980c5b40d183c3c7fbdf8d9df376b78b8baac13a47397" +
        "2d450b7690c1022966a0247cf65345560795553f079d0a444963d0bb371cbe09" +
        "3b65e9cffcaa0496f8ef3c3dbec042fe358f303bcc2659cd972fb492ca608e0c"

    private const val EAPI_KEY = "e82ckenh8dichen8"

    /** Digest path segment used verbatim by NetEase (music.163.com core.js). */
    private const val EAPI_PATH_SEGMENT = "-36cd479b6b5-"

    /**
     * Encrypts a JSON payload for a `/weapi/...` endpoint.
     * Returns the form body fields: `params` (double AES-128-CBC + base64) and
     * `encSecKey` (precomputed RSA ciphertext of the secret key).
     */
    fun weapi(payload: ByteArray): Map<String, String> {
        val first = AesCrypt.encrypt(payload, WEAPI_PRESET_KEY, WEAPI_IV)
        val firstBase64 = java.util.Base64.getEncoder().encodeToString(first)
        val second = AesCrypt.encrypt(firstBase64.toByteArray(Charsets.UTF_8), WEAPI_SECRET_KEY, WEAPI_IV)
        val params = java.util.Base64.getEncoder().encodeToString(second)
        return mapOf("params" to params, "encSecKey" to WEAPI_ENC_SEC_KEY)
    }

    /**
     * Encrypts a JSON payload for an `/eapi/...` endpoint.
     *
     * @param apiPath the internal API path, e.g. "/api/song/enhance/player/url/v1"
     * @return the form body field `params` (AES-128-ECB, uppercase hex)
     */
    fun eapi(apiPath: String, payload: ByteArray): Map<String, String> {
        val text = String(payload, Charsets.UTF_8)
        val message = "nobody$apiPath" + "use$text" + "md5forencrypt"
        val digest = AesCrypt.md5HexLower(message)
        val data = apiPath + EAPI_PATH_SEGMENT + text + EAPI_PATH_SEGMENT + digest
        val encrypted = AesCrypt.encrypt(data.toByteArray(Charsets.UTF_8), EAPI_KEY, iv = null)
        return mapOf("params" to encrypted.toUpperHex())
    }

    /** Uppercase hex encoding; Swift `String(format: "%02X", $0)` per byte. */
    private fun ByteArray.toUpperHex(): String = buildString(size * 2) {
        for (byte in this@toUpperHex) {
            val value = byte.toInt() and 0xFF
            append(HEX_UPPER[value ushr 4])
            append(HEX_UPPER[value and 0x0F])
        }
    }

    private val HEX_UPPER = "0123456789ABCDEF".toCharArray()
}
