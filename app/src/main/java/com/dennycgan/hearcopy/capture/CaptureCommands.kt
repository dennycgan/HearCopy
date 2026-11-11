package com.dennycgan.hearcopy.capture

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.util.Log
import com.dennycgan.hearcopy.data.ApiKeyDelete
import com.dennycgan.hearcopy.data.ApiKeySave
import com.dennycgan.hearcopy.data.ApiKeyStatus
import com.dennycgan.hearcopy.data.SettingsRepository
import com.dennycgan.hearcopy.notify.CaptureNotifications
import com.dennycgan.hearcopy.state.CaptureEvent
import com.dennycgan.hearcopy.state.CaptureStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * The only way ui/ and overlay/ act on capture. In-process; only
 * [startSession] goes through an Intent, because it has to start the
 * service.
 */
class CaptureCommands(
    private val context: Context,
    private val store: CaptureStateStore,
    private val notifications: CaptureNotifications,
    private val settings: SettingsRepository,
    /** Application-wide, main thread: a key write and its report finish even if the screen goes. */
    private val scope: CoroutineScope,
    private val handle: () -> CaptureSessionHandle?,
) {

    sealed interface KeySaveResult {
        /** [hint] is the saved key's last four characters. */
        data class Saved(val hint: String) : KeySaveResult

        /** The key breaks the save rule; nothing was written. */
        data object Invalid : KeySaveResult

        data object Failed : KeySaveResult
    }

    enum class KeyDeleteResult { Deleted, Failed }

    /** Whether the main screen was opened from the authorisation-lost alert. */
    fun isAlertIntent(intent: Intent?): Boolean = notifications.isAlertIntent(intent)

    /** The system dialog that asks for capture consent. */
    fun consentIntent(context: Context): Intent =
        context.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()

    /**
     * Starts the capture service with a consent result. Call from the
     * visible Activity: this uses `startService`, and the service promotes
     * itself to the foreground in `onStartCommand`.
     */
    fun startSession(resultCode: Int, data: Intent) {
        context.startService(CaptureService.startIntent(context, resultCode, data))
    }

    /** The single write path for the two permission facts. */
    fun reportPermissions(overlayGranted: Boolean, recordAudioGranted: Boolean) {
        store.dispatch(CaptureEvent.PermissionsReported(overlayGranted, recordAudioGranted))
    }

    /** A tap on hearcopyTouch. Does nothing when no service is running. */
    fun tap() {
        handle()?.onTap()
    }

    /** Reads whether a key is stored and reports it; called at process start. */
    fun loadApiKeyStatus() {
        scope.launch { refreshApiKey() }
    }

    /** Reads and reports whether a key is stored; returns its hint, or null when there is none. */
    suspend fun refreshApiKey(): String? = scope.async {
        val status = settings.apiKeyStatus()
        log("api key status: present=${status is ApiKeyStatus.Present}")
        store.dispatch(CaptureEvent.ApiKeyReported(status is ApiKeyStatus.Present))
        (status as? ApiKeyStatus.Present)?.hint
    }.await()

    /** Trims [raw], applies the key rule, stores it and reports it. */
    suspend fun saveApiKey(raw: String): KeySaveResult {
        val key = normaliseApiKey(raw) ?: return KeySaveResult.Invalid
        return scope.async {
            when (val saved = settings.saveApiKey(key)) {
                is ApiKeySave.Saved -> {
                    log("api key saved")
                    store.dispatch(CaptureEvent.ApiKeyReported(true))
                    KeySaveResult.Saved(saved.hint)
                }
                ApiKeySave.Failed -> {
                    log("api key save failed")
                    KeySaveResult.Failed
                }
            }
        }.await()
    }

    /**
     * Deletes the key. Once the storage reports it deleted, the key is
     * reported absent and a running session ends (KeyRemoved, no alert).
     */
    suspend fun deleteApiKey(): KeyDeleteResult = scope.async {
        when (settings.deleteApiKey()) {
            ApiKeyDelete.Deleted -> {
                log("api key deleted")
                store.dispatch(CaptureEvent.ApiKeyReported(false))
                handle()?.endForKeyRemoved()
                KeyDeleteResult.Deleted
            }
            ApiKeyDelete.Failed -> {
                log("api key delete failed")
                KeyDeleteResult.Failed
            }
        }
    }.await()

    private fun log(message: String) {
        Log.d(TAG, message)
    }

    private companion object {
        const val TAG = "HearCopyCapture"
    }
}
