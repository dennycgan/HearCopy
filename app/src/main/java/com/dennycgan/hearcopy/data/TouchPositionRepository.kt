package com.dennycgan.hearcopy.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * hearcopyTouch's resting position in panel pixels (ADR-017), in its own
 * preferences DataStore in no_backup, so a corrupt file can never take the
 * API key or the transcript with it. Excluded from backup like everything
 * in no_backup.
 */
class TouchPositionRepository internal constructor(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(
        createStore(File(context.noBackupFilesDir, STORE_PATH), CoroutineScope(Dispatchers.IO + SupervisorJob())),
    )

    /** Replaces the stored position; both coordinates in one edit. False if the write failed. */
    suspend fun save(x: Int, y: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            dataStore.edit {
                it[X] = x
                it[Y] = y
            }
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            false
        }
    }

    /** The stored (x, y), or null when there is none, only half of it, or it cannot be read. */
    suspend fun load(): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        try {
            val preferences = dataStore.data.first()
            val x = preferences[X]
            val y = preferences[Y]
            if (x != null && y != null) x to y else null
        } catch (e: IOException) {
            null
        }
    }

    internal companion object {
        const val STORE_PATH = "datastore/touch.preferences_pb"
        val X = intPreferencesKey("touch_panel_x")
        val Y = intPreferencesKey("touch_panel_y")

        fun createStore(file: File, scope: CoroutineScope): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = scope,
                produceFile = { file },
            )
    }
}
