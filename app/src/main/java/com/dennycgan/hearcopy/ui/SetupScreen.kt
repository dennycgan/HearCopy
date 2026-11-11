package com.dennycgan.hearcopy.ui

import android.content.ClipData
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.dennycgan.hearcopy.R
import com.dennycgan.hearcopy.state.CaptureState
import com.dennycgan.hearcopy.state.CaptureStatus
import com.dennycgan.hearcopy.state.FailureReason
import com.dennycgan.hearcopy.state.Setup
import kotlinx.coroutines.launch

/**
 * Setup steps in auto-advance order. [Checking] and [ApiKey] open no
 * system screen: the first waits for storage, the second for the key field.
 */
enum class SetupStep { Checking, ApiKey, Overlay, Microphone, Consent, Done }

/** The next missing step, from the reported setup facts. The key comes first. */
fun nextStep(setup: Setup): SetupStep = when {
    setup.apiKeyPresent == null -> SetupStep.Checking
    setup.apiKeyPresent == false -> SetupStep.ApiKey
    !setup.overlayGranted -> SetupStep.Overlay
    !setup.recordAudioGranted -> SetupStep.Microphone
    !setup.projectionActive -> SetupStep.Consent
    else -> SetupStep.Done
}

/** The outcome of the last key save or delete, shown under the key field. */
enum class KeyMessage { Invalid, SaveFailed, DeleteFailed }

/**
 * The temporary key field (replaced in Cap 3). [input] is held in memory
 * only and never written to saved instance state.
 */
class ApiKeyUi(
    val input: String,
    val hint: String?,
    val message: KeyMessage?,
    val busy: Boolean,
    val onInput: (String) -> Unit,
    val onSave: () -> Unit,
    val onDelete: () -> Unit,
)

/**
 * Four setup rows, the key field, one action button for the next missing
 * step, one status line, and the last transcript. Scrolls as a whole.
 */
@Composable
fun SetupScreen(
    state: CaptureState,
    nextStep: SetupStep,
    declined: Boolean,
    awaitingSession: Boolean,
    apiKey: ApiKeyUi,
    onAction: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        // Scrolls: a transcript of a 5 min clip is far longer than the screen.
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineSmall)
            SetupRow(R.string.setup_api_key, state.setup.apiKeyPresent)
            // Hidden until storage has been read, so a stored key never
            // flashes as missing.
            state.setup.apiKeyPresent?.let { present -> ApiKeySection(apiKey, present) }
            SetupRow(R.string.setup_overlay, state.setup.overlayGranted)
            SetupRow(R.string.setup_microphone, state.setup.recordAudioGranted)
            SetupRow(R.string.setup_capture, state.setup.projectionActive)
            if (nextStep == SetupStep.Overlay) {
                Text(text = stringResource(R.string.setup_overlay_hint), style = MaterialTheme.typography.bodyMedium)
            }
            // Never hidden while a session is starting: if the service fails,
            // this button is the way out.
            actionLabel(nextStep)?.let { label ->
                Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(label))
                }
            }
            Text(
                text = stringResource(statusLine(state, nextStep, declined, awaitingSession)),
                style = MaterialTheme.typography.bodyLarge,
            )
            TranscriptSection(state.lastTranscript)
        }
    }
}

@Composable
private fun ApiKeySection(apiKey: ApiKeyUi, present: Boolean) {
    if (present && apiKey.hint != null) {
        Text(text = stringResource(R.string.api_key_saved, apiKey.hint), style = MaterialTheme.typography.bodyMedium)
    }
    OutlinedTextField(
        value = apiKey.input,
        onValueChange = apiKey.onInput,
        modifier = Modifier.fillMaxWidth(),
        enabled = !apiKey.busy,
        label = { Text(text = stringResource(R.string.api_key_label)) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        singleLine = true,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = apiKey.onSave, enabled = !apiKey.busy) {
            Text(text = stringResource(R.string.action_save_key))
        }
        if (present) {
            OutlinedButton(onClick = apiKey.onDelete, enabled = !apiKey.busy) {
                Text(text = stringResource(R.string.action_delete_key))
            }
        }
    }
    apiKey.message?.let { message ->
        Text(
            text = stringResource(keyMessage(message)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** [done] null: not known yet, nothing shown. */
@Composable
private fun SetupRow(@StringRes label: Int, done: Boolean?) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = stringResource(label), style = MaterialTheme.typography.bodyLarge)
        if (done != null) {
            Text(
                text = stringResource(if (done) R.string.setup_done else R.string.setup_needed),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

/** Null for the steps without an action button (and Done). */
@StringRes
private fun actionLabel(step: SetupStep): Int? = when (step) {
    SetupStep.Overlay -> R.string.action_overlay
    SetupStep.Microphone -> R.string.action_microphone
    SetupStep.Consent -> R.string.action_capture
    SetupStep.Checking, SetupStep.ApiKey, SetupStep.Done -> null
}

@StringRes
private fun keyMessage(message: KeyMessage): Int = when (message) {
    KeyMessage.Invalid -> R.string.api_key_invalid
    KeyMessage.SaveFailed -> R.string.api_key_save_failed
    KeyMessage.DeleteFailed -> R.string.api_key_delete_failed
}

@StringRes
private fun statusLine(state: CaptureState, step: SetupStep, declined: Boolean, awaitingSession: Boolean): Int = when {
    awaitingSession -> R.string.status_starting
    declined -> R.string.status_declined
    step != SetupStep.Done -> R.string.status_setup
    else -> when (val status = state.status) {
        CaptureStatus.Ready -> R.string.status_ready
        CaptureStatus.Preparing -> R.string.status_preparing
        is CaptureStatus.Recording -> R.string.status_recording
        CaptureStatus.Processing -> R.string.status_processing
        CaptureStatus.Completed -> R.string.status_completed
        is CaptureStatus.Failed -> failureStatusRes(status.reason)
    }
}

/** The fixed text of each failure reason; the failure notification uses the same strings. */
@StringRes
internal fun failureStatusRes(reason: FailureReason): Int = when (reason) {
    FailureReason.PreparingTimeout -> R.string.status_failed_timeout
    FailureReason.InitFailed -> R.string.status_failed_init
    FailureReason.ReadError -> R.string.status_failed_read
    FailureReason.NoAudio -> R.string.status_failed_no_audio
    FailureReason.NoSpeech -> R.string.status_failed_no_speech
    FailureReason.Unauthorized -> R.string.status_failed_unauthorized
    FailureReason.Forbidden -> R.string.status_failed_forbidden
    FailureReason.RateLimited -> R.string.status_failed_rate_limited
    FailureReason.QuotaExceeded -> R.string.status_failed_quota
    FailureReason.Timeout -> R.string.status_failed_transcription_timeout
    FailureReason.Network -> R.string.status_failed_network
    FailureReason.Server -> R.string.status_failed_server
    FailureReason.Rejected -> R.string.status_failed_rejected
    FailureReason.Malformed -> R.string.status_failed_malformed
    FailureReason.TooLarge -> R.string.status_failed_too_large
    FailureReason.KeyUnavailable -> R.string.status_failed_key_unavailable
}

/**
 * The last transcript: natively selectable text and Copy all at the bottom
 * right, or an empty state. Never cleared by a failure or a new recording.
 */
@Composable
private fun TranscriptSection(transcript: String?) {
    Text(text = stringResource(R.string.transcript_title), style = MaterialTheme.typography.titleMedium)
    if (transcript == null) {
        Text(text = stringResource(R.string.transcript_empty), style = MaterialTheme.typography.bodyMedium)
        return
    }
    SelectionContainer {
        Text(text = transcript, style = MaterialTheme.typography.bodyLarge)
    }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.transcript_title)
    // Android 12 and lower show no confirmation of their own. Reset for each new transcript.
    var copied by remember(transcript) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (copied) {
            Text(text = stringResource(R.string.transcript_copied), style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedButton(
            onClick = {
                scope.launch {
                    clipboard.setClipEntry(ClipData.newPlainText(label, transcript).toClipEntry())
                    copied = true
                }
            },
        ) {
            Text(text = stringResource(R.string.action_copy_all))
        }
    }
}
