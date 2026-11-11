package com.dennycgan.hearcopy

import android.content.Context
import android.content.pm.ApplicationInfo
import com.dennycgan.hearcopy.capture.AudioSourceFactory
import com.dennycgan.hearcopy.capture.CaptureCommands
import com.dennycgan.hearcopy.capture.CaptureFiles
import com.dennycgan.hearcopy.capture.CaptureSessionHandle
import com.dennycgan.hearcopy.capture.PlaybackCaptureSource
import com.dennycgan.hearcopy.data.SettingsRepository
import com.dennycgan.hearcopy.data.TranscriptRepository
import com.dennycgan.hearcopy.notify.CaptureNotifications
import com.dennycgan.hearcopy.state.CaptureStateStore
import com.dennycgan.hearcopy.transcription.TranscriptionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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

    /** Application-wide work on the main thread that must outlive a screen (key save and delete). */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The stored API key (ADR-013); one instance per process, as DataStore requires. */
    val settings = SettingsRepository(appContext)

    /** The last transcript (ADR-014); its own DataStore file, one instance per process. */
    val transcripts = TranscriptRepository(appContext)

    /** OpenAI gpt-transcribe with the production endpoint and timeouts (ADR-012). */
    val transcriptionClient = TranscriptionClient()

    val captureCommands =
        CaptureCommands(appContext, store, notifications, settings, transcripts, appScope) { sessionHandle }
}
