package com.dennycgan.hearcopy.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dennycgan.hearcopy.R
import com.dennycgan.hearcopy.state.CaptureState
import com.dennycgan.hearcopy.state.CaptureStatus
import com.dennycgan.hearcopy.state.FailureReason
import com.dennycgan.hearcopy.state.Setup

/** Setup steps in auto-advance order. */
enum class SetupStep { Overlay, Microphone, Consent, Done }

/** The next missing step, from the reported setup facts. */
fun nextStep(setup: Setup): SetupStep = when {
    !setup.overlayGranted -> SetupStep.Overlay
    !setup.recordAudioGranted -> SetupStep.Microphone
    !setup.projectionActive -> SetupStep.Consent
    else -> SetupStep.Done
}

/** Three setup rows, one action button for the next missing step, one status line. */
@Composable
fun SetupScreen(
    state: CaptureState,
    nextStep: SetupStep,
    declined: Boolean,
    awaitingSession: Boolean,
    onAction: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineSmall)
            SetupRow(R.string.setup_overlay, state.setup.overlayGranted)
            SetupRow(R.string.setup_microphone, state.setup.recordAudioGranted)
            SetupRow(R.string.setup_capture, state.setup.projectionActive)
            if (nextStep == SetupStep.Overlay) {
                Text(text = stringResource(R.string.setup_overlay_hint), style = MaterialTheme.typography.bodyMedium)
            }
            // Never hidden while a session is starting: if the service fails,
            // this button is the way out.
            if (nextStep != SetupStep.Done) {
                Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(actionLabel(nextStep)))
                }
            }
            Text(
                text = stringResource(statusLine(state, nextStep, declined, awaitingSession)),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun SetupRow(@StringRes label: Int, done: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = stringResource(label), style = MaterialTheme.typography.bodyLarge)
        Text(
            text = stringResource(if (done) R.string.setup_done else R.string.setup_needed),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@StringRes
private fun actionLabel(step: SetupStep): Int = when (step) {
    SetupStep.Overlay -> R.string.action_overlay
    SetupStep.Microphone -> R.string.action_microphone
    SetupStep.Consent, SetupStep.Done -> R.string.action_capture
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
        is CaptureStatus.Failed -> when (status.reason) {
            FailureReason.PreparingTimeout -> R.string.status_failed_timeout
            FailureReason.InitFailed -> R.string.status_failed_init
            FailureReason.ReadError -> R.string.status_failed_read
            FailureReason.NoAudio -> R.string.status_failed_no_audio
        }
    }
}
