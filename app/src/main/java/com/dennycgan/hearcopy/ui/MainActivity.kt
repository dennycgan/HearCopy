package com.dennycgan.hearcopy.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dennycgan.hearcopy.HearCopyApplication
import com.dennycgan.hearcopy.ui.theme.HearCopyTheme
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Temporary main screen (replaced in Cap 3). Walks the setup steps in order
 * on every resume: overlay permission, microphone, capture consent. A
 * refused step sets [declined], which stops auto-advance until the action
 * button is used. Single instance (`launchMode="singleTask"`).
 */
class MainActivity : ComponentActivity() {

    private val container by lazy { (application as HearCopyApplication).container }

    /**
     * Kept across recreation (configuration changes, process death); a new
     * Activity instance starts clear.
     */
    private var declined by mutableStateOf(false)

    /**
     * A consent was granted and the session is starting. The consent result
     * and the next onResume run before the service's onStartCommand, so
     * without this auto-advance would ask for consent again. In memory
     * only: after process death no service exists to clear it.
     */
    private var awaitingSession by mutableStateOf(false)

    /**
     * A step's system screen is open; its result has not arrived yet. Kept
     * across recreation: a configuration change (dark mode, font scale,
     * locale; the screen is portrait only) behind an open dialog resumes the
     * new instance before the result arrives, and pending results are always
     * delivered to the recreated instance, whose launcher callback clears it.
     */
    private var inFlight = false
    private var notificationsAsked = false

    /** Lives from onCreate to onDestroy, so it also runs while in the background. */
    private val uiScope = MainScope()

    private val overlayLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        inFlight = false
        // The settings page returns no result; read the permission itself.
        if (!Settings.canDrawOverlays(this)) declined = true
    }

    private val microphoneLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        inFlight = false
        if (!granted) declined = true
    }

    private val consentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        inFlight = false
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            awaitingSession = true
            container.captureCommands.startSession(result.resultCode, data)
        } else {
            declined = true
        }
    }

    // Non-blocking: the result is not used and never sets declined.
    private val notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        inFlight = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        declined = savedInstanceState?.getBoolean(KEY_DECLINED) ?: false
        inFlight = savedInstanceState?.getBoolean(KEY_IN_FLIGHT) ?: false
        // Back sends the app to the background, never closes it. API 31+
        // already does that for a root launcher activity; enabling this
        // there would also turn off the predictive back-to-home animation.
        val backToBackground = object : OnBackPressedCallback(Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        }
        onBackPressedDispatcher.addCallback(this, backToBackground)
        // Clear the guard once the session is up, even with this screen in
        // the background. Clearing only on resume missed a session that
        // ended before the next resume: the guard stayed set, the status
        // line stayed on "Starting" and consent was never asked again.
        uiScope.launch {
            container.store.state.collect { if (it.setup.projectionActive) awaitingSession = false }
        }
        setContent {
            val state by container.store.state.collectAsState()
            HearCopyTheme {
                SetupScreen(
                    state = state,
                    nextStep = nextStep(state.setup),
                    declined = declined,
                    awaitingSession = awaitingSession && !state.setup.projectionActive,
                    onAction = ::onActionButton,
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_DECLINED, declined)
        outState.putBoolean(KEY_IN_FLIGHT, inFlight)
    }

    /**
     * The single instance reached again (singleTask). Runs before onResume,
     * so a tap on the alert re-enables auto-advance for that resume. A new
     * instance needs no such check: it starts with [declined] clear, and
     * an instance restored after process death gets the alert intent here.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (container.captureCommands.isAlertIntent(intent)) declined = false
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        container.captureCommands.reportPermissions(overlayGranted(), recordAudioGranted())
        advance()
    }

    private fun advance() {
        if (container.store.state.value.setup.projectionActive) awaitingSession = false
        if (declined || inFlight) return
        if (shouldAskNotifications()) {
            notificationsAsked = true
            inFlight = true
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val step = nextStep(container.store.state.value.setup)
        if (step == SetupStep.Consent && awaitingSession) return
        launch(step)
    }

    /** The action button: retries the next step and clears [declined]. */
    private fun onActionButton() {
        if (inFlight) return
        declined = false
        launch(nextStep(container.store.state.value.setup))
    }

    private fun launch(step: SetupStep) {
        when (step) {
            SetupStep.Overlay -> {
                inFlight = true
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.fromParts("package", packageName, null))
                try {
                    overlayLauncher.launch(intent)
                } catch (_: ActivityNotFoundException) {
                    inFlight = false
                    declined = true
                }
            }
            SetupStep.Microphone -> {
                inFlight = true
                microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            SetupStep.Consent -> {
                inFlight = true
                consentLauncher.launch(container.captureCommands.consentIntent(this))
            }
            SetupStep.Done -> Unit
        }
    }

    private fun shouldAskNotifications(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !notificationsAsked &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    private fun overlayGranted(): Boolean = Settings.canDrawOverlays(this)

    private fun recordAudioGranted(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val KEY_DECLINED = "declined"
        const val KEY_IN_FLIGHT = "inFlight"
    }
}
