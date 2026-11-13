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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dennycgan.hearcopy.HearCopyApplication
import com.dennycgan.hearcopy.capture.CaptureCommands.KeyDeleteResult
import com.dennycgan.hearcopy.capture.CaptureCommands.KeySaveResult
import com.dennycgan.hearcopy.state.isFinishing
import com.dennycgan.hearcopy.ui.theme.HearCopyTheme
import com.dennycgan.hearcopy.ui.theme.hearCopyBarStyle
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The single Activity (`launchMode="singleTask"`) with two screens, Main
 * and Settings (ADR-018). While Main is shown it walks the setup steps in
 * order on every resume: API key (entered on Settings; nothing opens by
 * itself), overlay permission, microphone, capture consent. A refused step
 * sets [declined], which stops auto-advance until the status card is
 * tapped or the alert is opened.
 */
class MainActivity : ComponentActivity() {

    private val container by lazy { (application as HearCopyApplication).container }

    /** Saved in instance state; never the typed key (ADR-013). */
    private var screen by mutableStateOf(Screen.Main)

    /**
     * Kept across recreation (configuration changes, process death); a new
     * Activity instance starts clear.
     */
    private var declined by mutableStateOf(false)

    /**
     * The last microphone request was denied and Android no longer shows
     * the dialog: the card opens HearCopy's app info instead. Kept across
     * recreation like [declined]; cleared once the microphone is granted.
     */
    private var microphoneBlocked by mutableStateOf(false)

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

    /**
     * The key being typed. In memory only, never in saved instance state:
     * a saved Bundle can be written to disk by the system (ADR-013).
     */
    private var keyInput by mutableStateOf("")
    private var keyHint by mutableStateOf<String?>(null)
    private var keyMessage by mutableStateOf<KeyMessage?>(null)
    private var keyBusy by mutableStateOf(false)
    private var replacingKey by mutableStateOf(false)
    private var confirmingDelete by mutableStateOf(false)

    /**
     * apiKeyPresent as last seen by advance() or the state collector. The
     * collector advances only on a key that appeared after that, so a key
     * read between onResume and the collector's start is not missed, and
     * one that advance() already saw does not advance twice.
     */
    private var keySeenPresent = false

    /**
     * isFinishing as last seen by advance() or the state collector. While
     * the last upload finishes after a lost session, the consent step is
     * held (ADR-015); when it ends, the collector continues the setup once
     * if this screen is resumed, otherwise the next onResume does.
     */
    private var finishingSeen = false

    /** Lives from onCreate to onDestroy, so it also runs while in the background. */
    private val uiScope = MainScope()

    private val overlayLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        inFlight = false
        // The settings page returns no result; read the permission itself.
        if (!Settings.canDrawOverlays(this)) declined = true
    }

    private val microphoneLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        inFlight = false
        if (!granted) {
            declined = true
            // After a denial, no rationale means Android will not show the dialog again.
            microphoneBlocked = !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        }
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
        // The screens are always light: dark bar icons also in system dark mode.
        enableEdgeToEdge(statusBarStyle = hearCopyBarStyle(), navigationBarStyle = hearCopyBarStyle())
        super.onCreate(savedInstanceState)
        declined = savedInstanceState?.getBoolean(KEY_DECLINED) ?: false
        inFlight = savedInstanceState?.getBoolean(KEY_IN_FLIGHT) ?: false
        microphoneBlocked = savedInstanceState?.getBoolean(KEY_MICROPHONE_BLOCKED) ?: false
        screen = savedInstanceState?.getString(KEY_SCREEN)
            ?.let { saved -> Screen.entries.firstOrNull { it.name == saved } }
            ?: Screen.Main
        // Back sends the app to the background, never closes it. API 31+
        // already does that for a root launcher activity; enabling this
        // there would also turn off the predictive back-to-home animation.
        // Settings' BackHandler, added later, takes precedence while enabled.
        val backToBackground = object : OnBackPressedCallback(Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        }
        onBackPressedDispatcher.addCallback(this, backToBackground)
        // Clear the guard once the session is up, even with this screen in
        // the background. Clearing only on resume missed a session that
        // ended before the next resume: the status stayed on "Starting"
        // and consent was never asked again.
        //
        // A key that becomes present while this screen is resumed (read at
        // start, or saved) continues the setup once per transition, through
        // advance(), so a declined step is still not asked again; while
        // Settings is shown, advance() starts nothing and the return to
        // Main continues. A transition while not resumed is picked up by
        // the next onResume.
        uiScope.launch {
            container.store.state.collect {
                if (it.setup.projectionActive) awaitingSession = false
                val keyPresent = it.setup.apiKeyPresent == true
                val becamePresent = keyPresent && !keySeenPresent
                keySeenPresent = keyPresent
                val finishing = isFinishing(it)
                val finishingEnded = finishingSeen && !finishing
                finishingSeen = finishing
                if ((becamePresent || finishingEnded) && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) advance()
            }
        }
        uiScope.launch { keyHint = container.captureCommands.refreshApiKey() }
        setContent {
            val state by container.store.state.collectAsStateWithLifecycle()
            HearCopyTheme {
                BackHandler(enabled = screen == Screen.Settings) { showMain() }
                when (screen) {
                    Screen.Main -> MainScreen(
                        card = statusCard(
                            state = state,
                            awaitingSession = awaitingSession && !state.setup.projectionActive,
                            declined = declined,
                            microphoneBlocked = microphoneBlocked,
                        ),
                        transcript = state.lastTranscript,
                        onOpenSettings = { screen = Screen.Settings },
                        onCardTap = ::onCardTap,
                    )
                    Screen.Settings -> SettingsScreen(
                        apiKey = ApiKeyUi(
                            present = state.setup.apiKeyPresent,
                            input = keyInput,
                            hint = keyHint,
                            message = keyMessage,
                            busy = keyBusy,
                            replacing = replacingKey,
                            confirmingDelete = confirmingDelete,
                            onInput = { keyInput = it },
                            onSave = ::saveKey,
                            onReplace = {
                                replacingKey = true
                                keyMessage = null
                            },
                            onCancelReplace = ::resetKeyEditor,
                            onDeleteRequest = { confirmingDelete = true },
                            onDeleteConfirm = ::deleteKey,
                            onDeleteDismiss = { confirmingDelete = false },
                        ),
                        onBack = ::showMain,
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_DECLINED, declined)
        outState.putBoolean(KEY_IN_FLIGHT, inFlight)
        outState.putBoolean(KEY_MICROPHONE_BLOCKED, microphoneBlocked)
        outState.putString(KEY_SCREEN, screen.name)
    }

    /**
     * The single instance reached again (singleTask). Runs before onResume,
     * so a tap on the alert re-enables auto-advance for that resume, and a
     * tap on the alert or the transcription notification shows Main. Never
     * read from the start intent in onCreate: a recreation keeps the last
     * intent and would jump back to Main. A new instance starts on Main and
     * with [declined] clear anyway, and an instance restored after process
     * death gets the tap here.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val commands = container.captureCommands
        if (commands.isAlertIntent(intent)) declined = false
        if (commands.opensMain(intent)) showMain()
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        val recordAudio = recordAudioGranted()
        if (recordAudio) microphoneBlocked = false
        container.captureCommands.reportPermissions(overlayGranted(), recordAudio)
        advance()
    }

    /**
     * Shows Main and leaves the key editor. Returning while resumed
     * continues the setup once, as a resume would; otherwise the next
     * onResume does.
     */
    private fun showMain() {
        if (screen == Screen.Main) return
        screen = Screen.Main
        resetKeyEditor()
        confirmingDelete = false
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) advance()
    }

    private fun resetKeyEditor() {
        replacingKey = false
        keyInput = ""
        keyMessage = null
    }

    private fun advance() {
        val state = container.store.state.value
        keySeenPresent = state.setup.apiKeyPresent == true
        finishingSeen = isFinishing(state)
        if (state.setup.projectionActive) awaitingSession = false
        // Settings starts nothing; the return to Main continues.
        if (screen != Screen.Main || declined || inFlight) return
        if (shouldAskNotifications()) {
            notificationsAsked = true
            inFlight = true
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val step = nextStep(state.setup)
        // The ending service would ignore a start; the collector continues once it has ended.
        if (step == SetupStep.Consent && (awaitingSession || finishingSeen)) return
        launch(step)
    }

    private fun saveKey() {
        if (keyBusy) return
        keyBusy = true
        uiScope.launch {
            when (val result = container.captureCommands.saveApiKey(keyInput)) {
                is KeySaveResult.Saved -> {
                    keyHint = result.hint
                    resetKeyEditor()
                }
                KeySaveResult.Invalid -> keyMessage = KeyMessage.Invalid
                KeySaveResult.Failed -> keyMessage = KeyMessage.SaveFailed
            }
            keyBusy = false
        }
    }

    private fun deleteKey() {
        confirmingDelete = false
        if (keyBusy) return
        keyBusy = true
        uiScope.launch {
            when (container.captureCommands.deleteApiKey()) {
                KeyDeleteResult.Deleted -> {
                    keyHint = null
                    resetKeyEditor()
                }
                KeyDeleteResult.Failed -> keyMessage = KeyMessage.DeleteFailed
            }
            keyBusy = false
        }
    }

    /** A tap on the status card: clears [declined] and opens what the card names. */
    private fun onCardTap(tap: CardTap) {
        if (inFlight || tap == CardTap.None) return
        declined = false
        when (tap) {
            CardTap.OpenSettings -> screen = Screen.Settings
            CardTap.OpenAppInfo -> openAppInfo()
            CardTap.RetryStep -> launch(nextStep(container.store.state.value.setup))
            CardTap.None -> Unit
        }
    }

    /**
     * HearCopy's app info, where the microphone can be allowed. No result:
     * the next onResume reads the permission and continues the setup.
     */
    private fun openAppInfo() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            declined = true
        }
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
            // Nothing to open: storage is still being read, or the key is
            // entered on Settings (first launch does not open it by itself).
            SetupStep.Checking, SetupStep.ApiKey, SetupStep.Done -> Unit
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
        const val KEY_MICROPHONE_BLOCKED = "microphoneBlocked"
        const val KEY_SCREEN = "screen"
    }
}
