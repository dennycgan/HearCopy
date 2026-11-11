package com.dennycgan.hearcopy.data

import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The shared cipher code with a software AES-256 key in place of the Keystore key. */
class ApiKeyCipherTest {

    private val key = softwareKey()
    private val plaintext = "sk-test-fake"

    @Test
    fun `decrypt returns what encrypt sealed`() {
        assertEquals(plaintext, ApiKeyCipher.decrypt(key, ApiKeyCipher.encrypt(key, plaintext)))
    }

    @Test
    fun `blob is version, 12-byte IV, ciphertext and 16-byte tag`() {
        val blob = ApiKeyCipher.encrypt(key, plaintext)
        assertEquals(ApiKeyCipher.VERSION, blob[0])
        assertEquals(1 + 12 + plaintext.length + 16, blob.size)
        assertTrue(ApiKeyCipher.isWellFormed(blob))
    }

    @Test
    fun `every encryption uses a fresh IV`() {
        val first = ApiKeyCipher.encrypt(key, plaintext)
        val second = ApiKeyCipher.encrypt(key, plaintext)
        assertFalse(first.copyOfRange(1, 13).contentEquals(second.copyOfRange(1, 13)))
    }

    @Test
    fun `the blob does not contain the plaintext`() {
        val blob = ApiKeyCipher.encrypt(key, plaintext)
        assertFalse(String(blob, Charsets.ISO_8859_1).contains(plaintext))
    }

    @Test
    fun `a changed IV, ciphertext or tag byte fails the tag check`() {
        val blob = ApiKeyCipher.encrypt(key, plaintext)
        for (index in listOf(1, 12, 13, 13 + plaintext.length - 1, blob.size - 1)) {
            val tampered = blob.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertBadTag("byte $index") { ApiKeyCipher.decrypt(key, tampered) }
        }
    }

    @Test
    fun `another key fails the tag check`() {
        val blob = ApiKeyCipher.encrypt(key, plaintext)
        assertBadTag("other key") { ApiKeyCipher.decrypt(softwareKey(), blob) }
    }

    @Test
    fun `a blob sealed without the API key label fails the tag check`() {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
        val blob = byteArrayOf(ApiKeyCipher.VERSION) + cipher.iv + cipher.doFinal(plaintext.toByteArray())
        assertBadTag("no AAD") { ApiKeyCipher.decrypt(key, blob) }
    }

    @Test
    fun `a short blob or an unknown version is malformed`() {
        val blob = ApiKeyCipher.encrypt(key, plaintext)
        assertFalse(ApiKeyCipher.isWellFormed(ByteArray(0)))
        assertFalse(ApiKeyCipher.isWellFormed(blob.copyOf(1 + 12 + 16)))
        assertFalse(ApiKeyCipher.isWellFormed(blob.copyOf().also { it[0] = 2 }))
        assertTrue(ApiKeyCipher.isWellFormed(blob.copyOf(1 + 12 + 16 + 1)))
    }

    private fun assertBadTag(label: String, block: () -> Unit) {
        try {
            block()
            fail("$label: decrypt succeeded")
        } catch (_: AEADBadTagException) {
            // expected
        }
    }

    private fun softwareKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
}
