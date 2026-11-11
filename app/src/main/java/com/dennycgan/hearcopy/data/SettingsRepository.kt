package com.dennycgan.hearcopy.data

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.io.IOException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Whether a key is stored, from the startup and save/delete checks. Never decrypts. */
sealed interface ApiKeyStatus {
    /** [hint] is the key's last four characters, stored in plain text at save time. */
    data class Present(val hint: String) : ApiKeyStatus

    data object Absent : ApiKeyStatus
}

sealed interface ApiKeySave {
    data class Saved(val hint: String) : ApiKeySave

    /** Nothing was written. */
    data object Failed : ApiKeySave
}

enum class ApiKeyDelete {
    /** The Keystore key is gone; the stored blob can no longer be decrypted. */
    Deleted,

    /** The Keystore key could not be deleted; nothing changed. */
    Failed,
}

/** The decrypted key, read right before an upload (phase 2.4). */
sealed interface ApiKeyRead {
    class Key(val value: String) : ApiKeyRead {
        override fun toString(): String = "Key(redacted)"
    }

    data object Absent : ApiKeyRead

    /** The key can never be read again; blob, hint and Keystore key were deleted. */
    data object Unreadable : ApiKeyRead

    /** A Keystore error that may pass; nothing was deleted. */
    data object Unavailable : ApiKeyRead
}

/**
 * The stored OpenAI API key (ADR-013): AES-GCM ciphertext and a four-character
 * hint in a preferences DataStore in no_backup, sealed with a Keystore key.
 * Calls are serialised and run off the main thread. The plaintext is
 * returned only by [readApiKey]. Takes the key as given; the save rule is
 * applied by the caller.
 */
class SettingsRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val keys: SecretKeySource,
) {

    constructor(context: Context) : this(
        createStore(File(context.noBackupFilesDir, STORE_PATH), CoroutineScope(Dispatchers.IO + SupervisorJob())),
        KeystoreKeys(),
    )

    private val mutex = Mutex()

    suspend fun saveApiKey(key: String): ApiKeySave = serial {
        try {
            val secret = (if (keys.hasKey()) keys.key() else null) ?: keys.createKey()
            val blob = ApiKeyCipher.encrypt(secret, key)
            val hint = key.takeLast(HINT_LENGTH)
            dataStore.edit {
                it[BLOB] = blob
                it[HINT] = hint
            }
            ApiKeySave.Saved(hint)
        } catch (e: Exception) {
            rethrowCancellation(e)
            ApiKeySave.Failed
        }
    }

    /**
     * Presence without decrypting. An unusable blob, or a blob whose
     * Keystore key is absent, is deleted. A Keystore error keeps the key as
     * present and deletes nothing; the decrypt before an upload decides.
     */
    suspend fun apiKeyStatus(): ApiKeyStatus = serial {
        val prefs = preferences()
        val blob = prefs[BLOB]
        val hint = prefs[HINT]
        when {
            blob == null && hint == null -> ApiKeyStatus.Absent
            blob == null || hint == null || !ApiKeyCipher.isWellFormed(blob) -> {
                clearPreferences()
                ApiKeyStatus.Absent
            }
            else -> {
                val hasKey = try {
                    keys.hasKey()
                } catch (e: Exception) {
                    rethrowCancellation(e)
                    true
                }
                if (hasKey) {
                    ApiKeyStatus.Present(hint)
                } else {
                    clearPreferences()
                    ApiKeyStatus.Absent
                }
            }
        }
    }

    /**
     * Deletes the Keystore key first: from then on the blob is unreadable,
     * so the delete is done even if clearing the DataStore fails; the next
     * [apiKeyStatus] removes the leftover blob.
     */
    suspend fun deleteApiKey(): ApiKeyDelete = serial {
        try {
            keys.deleteKey()
        } catch (e: Exception) {
            rethrowCancellation(e)
            return@serial ApiKeyDelete.Failed
        }
        clearPreferences()
        ApiKeyDelete.Deleted
    }

    suspend fun readApiKey(): ApiKeyRead = serial {
        val blob = preferences()[BLOB] ?: return@serial ApiKeyRead.Absent
        if (!ApiKeyCipher.isWellFormed(blob)) return@serial unreadable()
        try {
            val secret = (if (keys.hasKey()) keys.key() else null) ?: return@serial unreadable()
            ApiKeyRead.Key(ApiKeyCipher.decrypt(secret, blob))
        } catch (e: Exception) {
            rethrowCancellation(e)
            if (e.isPermanent()) unreadable() else ApiKeyRead.Unavailable
        }
    }

    private suspend fun unreadable(): ApiKeyRead {
        try {
            keys.deleteKey()
        } catch (e: Exception) {
            rethrowCancellation(e)
        }
        clearPreferences()
        return ApiKeyRead.Unreadable
    }

    private suspend fun preferences(): Preferences = try {
        dataStore.data.first()
    } catch (e: IOException) {
        emptyPreferences()
    }

    /** Best effort; a failure leaves a blob that the next check removes. */
    private suspend fun clearPreferences() {
        try {
            dataStore.edit {
                it.remove(BLOB)
                it.remove(HINT)
            }
        } catch (e: Exception) {
            rethrowCancellation(e)
        }
    }

    private suspend fun <T> serial(block: suspend () -> T): T =
        mutex.withLock { withContext(Dispatchers.IO) { block() } }

    private fun rethrowCancellation(e: Exception) {
        if (e is CancellationException) throw e
    }

    /** Failures after which the key can never be read again. */
    private fun Exception.isPermanent(): Boolean =
        this is AEADBadTagException ||
            this is KeyPermanentlyInvalidatedException ||
            this is UnrecoverableKeyException

    internal companion object {
        const val STORE_PATH = "datastore/settings.preferences_pb"
        const val HINT_LENGTH = 4
        val BLOB = byteArrayPreferencesKey("api_key_blob")
        val HINT = stringPreferencesKey("api_key_hint")

        fun createStore(file: File, scope: CoroutineScope): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
                produceFile = { file },
            )
    }
}
