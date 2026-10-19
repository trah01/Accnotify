package com.trah.accnotify.crypto

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object SenderEncryption {
    const val ALGORITHM = "aes-256-gcm"

    fun normalizeKey(value: String): String {
        val key = value.trim().lowercase()
        require(key.matches(Regex("[0-9a-f]{64}"))) { "请输入 64 位十六进制密钥" }
        return key
    }

    fun generateKey(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun decrypt(algorithm: String, nonce: String, ciphertext: String, key: String): String {
        require(algorithm == ALGORITHM) { "Unsupported encryption algorithm" }
        val keyBytes = normalizeKey(key).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val nonceBytes = Base64.getDecoder().decode(nonce)
        val encryptedBytes = Base64.getDecoder().decode(ciphertext)
        require(nonceBytes.size == 12 && encryptedBytes.size > 16 && encryptedBytes.size <= 4 * 1024 * 1024) {
            "Invalid encrypted message length"
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, nonceBytes))
        return cipher.doFinal(encryptedBytes).toString(Charsets.UTF_8)
    }
}
