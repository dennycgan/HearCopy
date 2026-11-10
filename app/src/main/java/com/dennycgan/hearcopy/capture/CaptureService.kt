package com.dennycgan.hearcopy.capture

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.dennycgan.hearcopy.HearCopyApplication
import com.dennycgan.hearcopy.notify.CaptureNotifications
import com.dennycgan.hearcopy.overlay.HearCopyTouch
import com.dennycgan.hearcopy.state.CaptureEvent
import com.dennycgan.hearcopy.state.CaptureStatus
import com.dennycgan.hearcopy.state.TapAction
import com.dennycgan.hearcopy.state.tapAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service of type mediaProjection. Holds one capture consent for
 * many recordings, shows hearcopyTouch, and runs one [CaptureSession] per
 * recording attempt. Everything here runs on the main thread.
 */
class CaptureService : Service(), CaptureSessionHandle {

    /**
     * Why the session ends. [postsAlert]: the user did not end the session
     * and may be in another app when the button disappears.
     */
    enum class TeardownReason(val postsAlert: Boolean) {
        /** HearCopy was removed from Recents. */
        UserEnded(false),

        /** The projection was stopped from outside (another capture, the system). */
        ProjectionLost(true),

        /** RECORD_AUDIO or the overlay permission is missing. */
        PermissionLost(true),

        /** The continuous capture could not start or gave up (ADR-005). */
        CaptureLost(true),

        /** Our own safety net, or a rejected consent result with HearCopy in front. */
        Internal(false),
    }

    private val container by lazy { (application as HearCopyApplication).container }
    private val store by lazy { container.store }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var projection: MediaProjection? = null
    private var overlay: HearCopyTouch? = null
    private var session: CaptureSession? = null
    private var loop: CaptureLoop? = null
    private var overlayWatcher: AppOpsManager.OnOpChangedListener? = null

    /**
     * Set first thing in [teardown] and never cleared: an instance is not
     * reused after a teardown, so any later teardown call does nothing.
     */
    private var stopping = false

    /** Main-thread handler for the projection callback and the overlay re-check. */
    private val mainHandler = Handler(Looper.getMainLooper())

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (stopping) return
            log("projection stopped externally at=${SystemClock.elapsedRealtime()}")
            teardown(TeardownReason.ProjectionLost)
        }
    }

    override fun onCreate() {
        super.onCreate()
        container.sessionHandle = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A second getMediaProjection would stop the projection we hold and
        // fire our own onStop into teardown, so a further start is ignored.
        if (projection != null || stopping) {
            log("start ignored: a session is already active or ending")
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = intent?.consentData()
        if (resultCode != Activity.RESULT_OK || data == null) {
            log("start without a consent result; stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startForeground(
                CaptureNotifications.SESSION_NOTIFICATION_ID,
                container.notifications.sessionNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } catch (e: RuntimeException) {
            log("startForeground failed: ${e.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }

        val granted = try {
            getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, data)
        } catch (e: RuntimeException) {
            log("consent result rejected: ${e.javaClass.simpleName}")
            null
        }
        if (granted == null) {
            teardown(TeardownReason.Internal)
            return START_NOT_STICKY
        }
        granted.registerCallback(projectionCallback, mainHandler)
        projection = granted
        // An alert from an earlier session no longer applies.
        container.notifications.cancelAuthorisationLostAlert()
        store.dispatch(CaptureEvent.SessionStarted)
        if (!startLoop(granted)) return START_NOT_STICKY
        val touch = HearCopyTouch(this, store.state, container.captureCommands)
        overlay = touch
        if (!touch.show()) {
            // Without the button a recording cannot be stopped; never a false Ready.
            log("overlay could not be shown at session start")
            teardown(TeardownReason.PermissionLost)
            return START_NOT_STICKY
        }
        watchOverlayPermission()
        log("session started")
        return START_NOT_STICKY
    }

    /**
     * Starts the session's continuous capture (ADR-005) while HearCopy is
     * still in front. The platform mutes media once here, unheard when
     * nothing plays.
     */
    private fun startLoop(granted: MediaProjection): Boolean {
        // Checked here, never read from the store: the reported value may be stale.
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            log("RECORD_AUDIO missing at session start")
            teardown(TeardownReason.PermissionLost)
            return false
        }
        loop = CaptureLoop(
            sourceFactory = { container.audioSourceFactory(granted) },
            scope = scope,
            clock = SystemClock::elapsedRealtime,
            log = ::log,
            onLost = { teardown(TeardownReason.CaptureLost) },
        ).also { it.start() }
        return true
    }

    /**
     * Revoking the overlay permission is an app-op change: the process lives
     * and the window manager only hides the button, so the session would
     * keep capturing with no way to stop a recording.
     */
    private fun watchOverlayPermission() {
        // Binder thread. Also fires for other packages and when the
        // permission is granted, so it only schedules a re-check on main.
        val listener = AppOpsManager.OnOpChangedListener { _, _ -> mainHandler.post(::recheckOverlayPermission) }
        getSystemService(AppOpsManager::class.java)
            .startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, listener)
        overlayWatcher = listener
    }

    private fun stopWatchingOverlayPermission() {
        val listener = overlayWatcher ?: return
        overlayWatcher = null
        getSystemService(AppOpsManager::class.java).stopWatchingMode(listener)
    }

    private fun recheckOverlayPermission() {
        if (stopping || projection == null) return
        // The check the main screen uses; the raw op mode can be MODE_DEFAULT.
        if (Settings.canDrawOverlays(this)) return
        log("overlay permission revoked")
        teardown(TeardownReason.PermissionLost)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        log("onTaskRemoved")
        teardown(TeardownReason.UserEnded)
        super.onTaskRemoved(rootIntent)
    }

    override fun onTap() {
        if (projection == null || stopping) return
        when (tapAction(store.state.value.status)) {
            TapAction.Start -> startCapture()
            TapAction.Stop -> session?.stop()
            TapAction.Ignore -> log("tap ignored: Preparing")
            TapAction.Reject -> Unit
        }
    }

    private fun startCapture() {
        // Checked here, never read from the store: the reported value may be stale.
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            log("RECORD_AUDIO missing at tap")
            teardown(TeardownReason.PermissionLost)
            return
        }
        val running = loop ?: return
        val before = store.state.value.attempt
        val after = store.dispatch(CaptureEvent.TapStart)
        if (after.status != CaptureStatus.Preparing || after.attempt <= before) return

        // Monotonic, the time base of the "clip start" log: pre-tap audio = this - first frame.
        log("tap: attempt=${after.attempt} starting at ${System.nanoTime() / NANOS_PER_MILLI} ms monotonic")
        val next = CaptureSession(
            attempt = after.attempt,
            outputFile = container.captureFiles.newTempFile(after.attempt),
            store = store,
            scope = scope,
            // Monotonic and counts deep sleep. The session's timers still run
            // on Main (uptime), which stops in deep sleep; accepted because
            // the platform holds a wakelock while a record is active, so a
            // timer can only fire late, never not at all.
            clock = SystemClock::elapsedRealtime,
            log = ::log,
        )
        session = next
        next.start()
        if (!running.attach(next)) next.failUnattached()
        scope.launch { onResult(next, next.result.await()) }
    }

    private suspend fun onResult(finished: CaptureSession, result: CaptureResult) {
        if (session === finished) session = null
        log(
            when (result) {
                is CaptureResult.Saved -> "result: saved ${result.file.name}"
                is CaptureResult.Failed -> "result: failed ${result.reason}"
                CaptureResult.Cancelled -> "result: cancelled"
                CaptureResult.TooShort -> "result: too short"
            },
        )
        if (result is CaptureResult.Saved) {
            val ok = withContext(Dispatchers.IO) { container.captureFiles.keepOrDelete(result.file) }
            if (!ok) log("saved clip could not be kept or deleted cleanly")
        }
    }

    /**
     * Ends the session. Main thread only: a callback or re-check posted
     * meanwhile runs after it and stops at [stopping].
     */
    private fun teardown(reason: TeardownReason) {
        if (stopping) return
        stopping = true
        log("teardown reason=$reason alert=${reason.postsAlert} at=${SystemClock.elapsedRealtime()}")
        stopWatchingOverlayPermission()
        overlay?.hide()
        overlay = null
        log("teardown: overlay hidden at=${SystemClock.elapsedRealtime()}")
        // The reader deletes the cancelled clip's temp file when it detaches it.
        session?.cancel()
        session = null
        loop?.close()
        loop = null
        // Own notification id, so removing the foreground notification keeps it.
        if (reason.postsAlert) container.notifications.postAuthorisationLostAlert()
        projection?.let {
            it.unregisterCallback(projectionCallback)
            it.stop()
        }
        projection = null
        store.dispatch(CaptureEvent.SessionEnded)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        log("teardown done at=${SystemClock.elapsedRealtime()}")
    }

    override fun onDestroy() {
        teardown(TeardownReason.Internal)
        // No-ops after a teardown; kept so nothing outlives the instance.
        stopWatchingOverlayPermission()
        mainHandler.removeCallbacksAndMessages(null)
        if (container.sessionHandle === this) container.sessionHandle = null
        scope.cancel()
        super.onDestroy()
    }

    private fun log(message: String) {
        Log.d(TAG, message)
    }

    private fun Intent.consentData(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(EXTRA_DATA)
        }

    companion object {
        private const val TAG = "HearCopyCapture"
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val EXTRA_RESULT_CODE = "com.dennycgan.hearcopy.extra.RESULT_CODE"
        private const val EXTRA_DATA = "com.dennycgan.hearcopy.extra.DATA"

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, CaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
    }
}
