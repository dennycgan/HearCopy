package com.dennycgan.hearcopy.capture

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import com.dennycgan.hearcopy.notify.CaptureNotifications
import com.dennycgan.hearcopy.state.CaptureEvent
import com.dennycgan.hearcopy.state.CaptureStateStore

/**
 * The only way ui/ and overlay/ act on capture. In-process; only
 * [startSession] goes through an Intent, because it has to start the
 * service.
 */
class CaptureCommands(
    private val context: Context,
    private val store: CaptureStateStore,
    private val notifications: CaptureNotifications,
    private val handle: () -> CaptureSessionHandle?,
) {

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
}
