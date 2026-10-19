package com.trah.accnotify.crypto

import android.util.Base64
import java.security.PrivateKey
import java.security.spec.MGF1ParameterSpec
import android.util.Log
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * Public-key message decryption utilities
 * 
 * Message format: Base64(keyLength[2 bytes] + RSA(AESKey) + Nonce[12 bytes] + AES-GCM(content))
 */
object E2ECrypto {

    private const val AES_KEY_SIZE = 32 // 256 bits
    private const val NONCE_SIZE = 12 // 96 bits
    private const val GCM_TAG_LENGTH = 128 // bits

    /**
     * Decrypt a message encrypted by the server
     *
     * @param encryptedContent Base64 encoded encrypted content
     * @param privateKey RSA private key for decrypting AES key
     * @return Decrypted plaintext string
     */
    fun decrypt(encryptedContent: String, privateKey: PrivateKey): String {
        // Decode base64
        val data = Base64.decode(encryptedContent, Base64.DEFAULT)
        require(data.size >= 2 + NONCE_SIZE + GCM_TAG_LENGTH / 8) { "Invalid encrypted message length" }

        // Extract key length (first 2 bytes, big-endian)
        val keyLength = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        require(keyLength > 0 && data.size >= 2 + keyLength + NONCE_SIZE + GCM_TAG_LENGTH / 8) {
            "Invalid encrypted key length"
        }

        // Extract encrypted AES key
        val encryptedAESKey = data.sliceArray(2 until 2 + keyLength)

        // Extract nonce
        val nonce = data.sliceArray(2 + keyLength until 2 + keyLength + NONCE_SIZE)

        // Extract ciphertext
        val ciphertext = data.sliceArray(2 + keyLength + NONCE_SIZE until data.size)

        // Decrypt AES key with RSA-OAEP
        val rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        // Older Android Keystore devices require SHA-1 for MGF1 even when
        // the OAEP digest is SHA-256. Never rely on provider defaults.
        val oaepSpec = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
        rsaCipher.init(Cipher.DECRYPT_MODE, privateKey, oaepSpec)
        val aesKey = rsaCipher.doFinal(encryptedAESKey)
        require(aesKey.size == AES_KEY_SIZE) { "Invalid AES key length" }

        // Decrypt message with AES-GCM
        val aesKeySpec = SecretKeySpec(aesKey, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
        val aesCipher = Cipher.getInstance("AES/GCM/NoPadding")
        aesCipher.init(Cipher.DECRYPT_MODE, aesKeySpec, gcmSpec)
        val plaintext = aesCipher.doFinal(ciphertext)

        return String(plaintext, Charsets.UTF_8)
    }

    /**
     * Try to decrypt, return null on failure
     */
    fun tryDecrypt(encryptedContent: String?, privateKey: PrivateKey?): String? {
        if (encryptedContent.isNullOrEmpty() || privateKey == null) return null
        return try {
            decrypt(encryptedContent, privateKey)
        } catch (e: Exception) {
            // Record the failure type without logging message content or keys.
            Log.e("E2ECrypto", "Message decryption failed: ${e.javaClass.simpleName}")
            null
        }
    }
}
