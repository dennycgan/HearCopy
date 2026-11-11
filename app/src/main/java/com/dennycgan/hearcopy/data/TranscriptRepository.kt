package com.dennycgan.hearcopy.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * The last transcript, in its own preferences DataStore in no_backup, so a
 * corrupt transcript file can never take the API key with it. Plain text,
 * local only; excluded from backup like everything in no_backup.
 */
class TranscriptRepository internal constructor(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(
        createStore(File(context.noBackupFilesDir, STORE_PATH), CoroutineScope(Dispatchers.IO + SupervisorJob())),
    )

    /** Replaces the stored transcript. False if the write failed. */
    suspend fun save(text: String): Boolean = withContext(Dispatchers.IO) {
        try {
            dataStore.edit { it[TEXT] = text }
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            false
        }
    }

    /** The stored transcript, or null when there is none or it cannot be read. */
    suspend fun load(): String? = withContext(Dispatchers.IO) {
        try {
            dataStore.data.first()[TEXT]
        } catch (e: IOException) {
            null
        }
    }

    internal companion object {
        const val STORE_PATH = "datastore/transcript.preferences_pb"
        val TEXT = stringPreferencesKey("last_transcript")

        fun createStore(file: File, scope: CoroutineScope): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
                produceFile = { file },
            )
    }
}
