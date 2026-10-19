package com.trah.accnotify.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SenderEncryptionTest {
    // NIST AES-256-GCM test vector: test-only all-zero key, nonce and plaintext.
    private val key = "00".repeat(32)
    private val nonce = Base64.getEncoder().encodeToString(ByteArray(12))
    private val ciphertext = "cea7403d4d606b6e074ec5d3baf39d18d0d1c8a799996bf0265b98b5d48ab919"
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun decryptKnownVector() {
        assertEquals("\u0000".repeat(16), SenderEncryption.decrypt(SenderEncryption.ALGORITHM,
            nonce, Base64.getEncoder().encodeToString(ciphertext), key))
    }

    @Test fun rejectsTamperedCiphertextAndWrongKey() {
        val changed = ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertTrue(runCatching { SenderEncryption.decrypt(SenderEncryption.ALGORITHM, nonce,
            Base64.getEncoder().encodeToString(changed), key) }.isFailure)
        assertTrue(runCatching { SenderEncryption.decrypt(SenderEncryption.ALGORITHM, nonce,
            Base64.getEncoder().encodeToString(ciphertext), "01".repeat(32)) }.isFailure)
    }

    @Test fun rejectsInvalidConfiguration() {
        assertTrue(runCatching { SenderEncryption.normalizeKey("password") }.isFailure)
        assertTrue(runCatching { SenderEncryption.decrypt("aes-cbc", nonce,
            Base64.getEncoder().encodeToString(ciphertext), key) }.isFailure)
        assertTrue(runCatching { SenderEncryption.decrypt(SenderEncryption.ALGORITHM, "AA==",
            Base64.getEncoder().encodeToString(ciphertext), key) }.isFailure)
    }

    @Test fun generatesDistinctValidKeys() {
        val first = SenderEncryption.generateKey()
        val second = SenderEncryption.generateKey()
        assertEquals(first, SenderEncryption.normalizeKey(first))
        assertEquals(second, SenderEncryption.normalizeKey(second))
        assertNotEquals(first, second)
    }
}
