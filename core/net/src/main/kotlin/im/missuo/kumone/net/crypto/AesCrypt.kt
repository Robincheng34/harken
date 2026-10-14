// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.net.crypto

/**
 * AES-128 / MD5 primitives for the NetEase weapi / eapi schemes.
 *
 * Ported 1:1 from the private helpers of `NeteaseCrypto.swift` (CommonCrypto +
 * CryptoKit). PKCS#5 padding in the JCA is byte-identical to the PKCS#7
 * padding used on iOS for the 16-byte AES block size.
 */
internal object AesCrypt {

    /** AES-128 encryption with PKCS7 padding; CBC when [iv] is given, ECB otherwise. */
    fun encrypt(data: ByteArray, key: String, iv: String?): ByteArray {
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        require(keyBytes.size == 16) { "AES-128 key must be 16 bytes, got ${keyBytes.size}" }
        val keySpec = javax.crypto.spec.SecretKeySpec(keyBytes, "AES")
        val cipher = if (iv == null) {
            javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding").apply {
                init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec)
            }
        } else {
            val ivBytes = iv.toByteArray(Charsets.UTF_8)
            require(ivBytes.size == 16) { "CBC IV must be 16 bytes, got ${ivBytes.size}" }
            javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                init(
                    javax.crypto.Cipher.ENCRYPT_MODE,
                    keySpec,
                    javax.crypto.spec.IvParameterSpec(ivBytes),
                )
            }
        }
        return cipher.doFinal(data)
    }

    /** Lowercase hex MD5 digest (CryptoKit `Insecure.MD5` equivalent). */
    fun md5HexLower(text: String): String {
        val digest = java.security.MessageDigest.getInstance("MD5")
            .digest(text.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            for (byte in digest) {
                val value = byte.toInt() and 0xFF
                append(HEX[value ushr 4])
                append(HEX[value and 0x0F])
            }
        }
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
