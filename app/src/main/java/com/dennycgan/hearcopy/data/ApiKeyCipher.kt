package com.dennycgan.hearcopy.data

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The AES key that encrypts the API key. On the device it lives in the
 * Android Keystore ([KeystoreKeys]); JVM tests use a software key.
 * Every method throws on a key store error.
 */
interface SecretKeySource {
    /** Whether the key exists. */
    fun hasKey(): Boolean

    /** The existing key, or null when there is none. */
    fun key(): SecretKey?

    /** Creates the key; called only when [hasKey] is false. */
    fun createKey(): SecretKey

    /** Deletes the key; does nothing when there is none. */
    fun deleteKey()
}

/**
 * AES-256-GCM sealing of the API key (ADR-013), javax.crypto only, so the
 * same code runs with a Keystore key and with a software key.
 *
 * Blob: version byte | 12-byte IV | ciphertext with the 128-bit tag. The IV
 * is chosen by the cipher on encryption (the Keystore does not accept one
 * from the caller) and read back from it.
 */
internal object ApiKeyCipher {

    const val VERSION: Byte = 1
    const val IV_BYTES = 12
    const val TAG_BYTES = 16

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val HEADER_BYTES = 1 + IV_BYTES
    private val AAD = "hearcopy/api-key/v1".toByteArray(Charsets.US_ASCII)

    fun encrypt(key: SecretKey, plaintext: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(AAD)
        val sealed = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        check(iv != null && iv.size == IV_BYTES) { "unexpected IV length" }
        return byteArrayOf(VERSION) + iv + sealed
    }

    /** Known version and long enough for a header, a tag and one byte of key. */
    fun isWellFormed(blob: ByteArray): Boolean =
        blob.size >= HEADER_BYTES + TAG_BYTES + 1 && blob[0] == VERSION

    /** Throws AEADBadTagException when the blob or key does not match. */
    fun decrypt(key: SecretKey, blob: ByteArray): String {
        require(isWellFormed(blob)) { "malformed blob" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, blob, 1, IV_BYTES))
        cipher.updateAAD(AAD)
        val plain = cipher.doFinal(blob, HEADER_BYTES, blob.size - HEADER_BYTES)
        return String(plain, Charsets.UTF_8)
    }
}
