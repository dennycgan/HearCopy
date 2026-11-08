package com.dennycgan.hearcopy

import android.content.Context
import android.content.pm.ApplicationInfo
import com.dennycgan.hearcopy.capture.AudioSourceFactory
import com.dennycgan.hearcopy.capture.CaptureCommands
import com.dennycgan.hearcopy.capture.CaptureFiles
import com.dennycgan.hearcopy.capture.CaptureSessionHandle
import com.dennycgan.hearcopy.capture.PlaybackCaptureSource
import com.dennycgan.hearcopy.notify.CaptureNotifications
import com.dennycgan.hearcopy.state.CaptureStateStore

/**
 * Hand-written dependency container. Concrete implementations are
 * constructed only here.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val store = CaptureStateStore()

    /** One source per capture session (and per restart), from the session's projection. */
    val audioSourceFactory: AudioSourceFactory = { PlaybackCaptureSource(it) }

    /** Debug builds keep the last valid clips (Cap 1 design D2). */
    val captureFiles = CaptureFiles(
        root = appContext.noBackupFilesDir,
        retainDebug = (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
    )

    val notifications = CaptureNotifications(appContext)

    /** The running capture service; main thread only. */
    var sessionHandle: CaptureSessionHandle? = null

    val captureCommands = CaptureCommands(appContext, store) { sessionHandle }
}
