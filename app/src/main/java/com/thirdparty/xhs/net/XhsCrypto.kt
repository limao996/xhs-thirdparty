package com.thirdparty.xhs.net

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Mirrors the transport crypto used by the examined app's backend.
 *
 * The backend wraps every request/response body in
 * AES/CBC/PKCS5Padding with a hard-coded key and IV (extracted from the app):
 *   key = "525202f9149e061d" (ASCII)
 *   iv  = "985204f4819ec31c" (ASCII)
 */
object XhsCrypto {
    private const val KEY = "525202f9149e061d"
    private const val IV = "985204f4819ec31c"

    /** Encrypt plain UTF-8 text -> raw bytes for the request body. */
    fun encrypt(plainText: String): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(KEY.toByteArray(Charsets.US_ASCII), "AES"),
            IvParameterSpec(IV.toByteArray(Charsets.US_ASCII))
        )
        return cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
    }

    /** Decrypt a raw AES response body -> UTF-8 JSON text. */
    fun decrypt(cipherBytes: ByteArray): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(KEY.toByteArray(Charsets.US_ASCII), "AES"),
            IvParameterSpec(IV.toByteArray(Charsets.US_ASCII))
        )
        return String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
    }

    /**
     * AES/ECB/NoPadding decrypt. Used for CDN images: any image URL containing
     * "codstatic" ships AES-ECB-encrypted bytes (same key) and must be
     * decrypted before the bitmap can be decoded. Mirrors the examined app's
     * AESUtils.zdecrypt.
     */
    @Suppress("GetInstance") // ECB 是 CDN 侧既定的加密方式（见 docs/PROTOCOL.md §2），不是我们的选择
    fun zdecrypt(cipherBytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY.toByteArray(Charsets.US_ASCII), "AES"))
        return cipher.doFinal(cipherBytes)
    }
}