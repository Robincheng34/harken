// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.net.crypto

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
    private const val WEAPI_SECRET_KEY = "kumone2026abcDEF"

    /** Precomputed `RSA(reverse("kumone2026abcDEF"))` under the public weapi key. */
    private const val WEAPI_ENC_SEC_KEY =
        "38cef2efdbcc1cfd6a44d81620dae5d23091f50ef27e01a1b1bb7e998e0fde2d" +
            "7ab6002a9e79a3c195f661cbde80e21e6245997b11b54d28407115822f95d447" +
            "7cc06b5a77de46fab6568410abf1229abef81b4c8588f386149010d190bb0b04" +
            "f064be330bd877a4d4b99514febbdb4335b10744b13d9f7ee24d314d6e62cdc9"

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
