package com.dennycgan.hearcopy.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The API key's AES key in the Android Keystore (ADR-013): non-exportable,
 * AES-256, GCM without padding, IV chosen by the Keystore, no user
 * authentication, no unlocked-device requirement, no StrongBox.
 * Checked on the device only; the Keystore does not run on the JVM.
 */
internal class KeystoreKeys : SecretKeySource {

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    override fun hasKey(): Boolean = keyStore().containsAlias(ALIAS)

    override fun key(): SecretKey? = keyStore().getKey(ALIAS, null) as? SecretKey

    override fun createKey(): SecretKey {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            .apply { init(spec) }
            .generateKey()
    }

    override fun deleteKey() {
        val store = keyStore()
        if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS)
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "hearcopy_api_key"
        const val KEY_BITS = 256
    }
}
