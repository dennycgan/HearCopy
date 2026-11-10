package com.dennycgan.hearcopy.state

import com.dennycgan.hearcopy.state.CaptureEvent.CaptureFailed
import com.dennycgan.hearcopy.state.CaptureEvent.CaptureSaved
import com.dennycgan.hearcopy.state.CaptureEvent.FirstAudio
import com.dennycgan.hearcopy.state.CaptureEvent.LimitReached
import com.dennycgan.hearcopy.state.CaptureEvent.PermissionsReported
import com.dennycgan.hearcopy.state.CaptureEvent.PreparingTimedOut
import com.dennycgan.hearcopy.state.CaptureEvent.SessionEnded
import com.dennycgan.hearcopy.state.CaptureEvent.SessionStarted
import com.dennycgan.hearcopy.state.CaptureEvent.TapStart
import com.dennycgan.hearcopy.state.CaptureEvent.TapStop
import com.dennycgan.hearcopy.state.CaptureStatus.Failed
import com.dennycgan.hearcopy.state.CaptureStatus.Preparing
import com.dennycgan.hearcopy.state.CaptureStatus.Processing
import com.dennycgan.hearcopy.state.CaptureStatus.Ready
import com.dennycgan.hearcopy.state.CaptureStatus.Recording

/**
 * Shortest clip that is kept, in milliseconds from the first audio to the
 * stop tap, on the overlay timer's clock. Shorter clips are discarded
 * without an upload.
 */
const val MIN_CLIP_MS = 1_000L

/**
 * Pure state transition. Any event that does not apply to the current
 * state returns the state unchanged.
 */
fun reduce(state: CaptureState, event: CaptureEvent): CaptureState = when (event) {
    is PermissionsReported -> state.copy(
        setup = state.setup.copy(
            overlayGranted = event.overlayGranted,
            recordAudioGranted = event.recordAudioGranted,
        ),
    )

    SessionStarted -> state.copy(setup = state.setup.copy(projectionActive = true))

    // Bumping the attempt makes every late event of the old session stale.
    SessionEnded -> state.copy(
        setup = state.setup.copy(projectionActive = false),
        status = Ready,
        attempt = state.attempt + 1,
    )

    // Gated only on the session: the reported RECORD_AUDIO value may be
    // stale, so the service checks the permission itself at tap time.
    TapStart ->
        if (state.setup.projectionActive && (state.status == Ready || state.status is Failed)) {
            state.copy(status = Preparing, attempt = state.attempt + 1)
        } else {
            state
        }

    // A clip stopped while the timer still shows 00:00 is discarded: back
    // to Ready, no Failed state, the attempt stays so its late events
    // find nothing to change.
    is TapStop -> when (val status = state.status) {
        is Recording ->
            if (event.atMs - status.startedAtMs < MIN_CLIP_MS) {
                state.copy(status = Ready)
            } else {
                state.copy(status = Processing)
            }
        else -> state
    }

    is FirstAudio -> state.forAttempt(event.attempt) {
        if (status == Preparing) copy(status = Recording(event.atMs)) else this
    }

    is LimitReached -> state.forAttempt(event.attempt) {
        if (status is Recording) copy(status = Processing) else this
    }

    is PreparingTimedOut -> state.forAttempt(event.attempt) {
        if (status == Preparing) copy(status = Failed(FailureReason.PreparingTimeout)) else this
    }

    is CaptureSaved -> state.forAttempt(event.attempt) {
        if (status == Processing) copy(status = Ready) else this
    }

    is CaptureFailed -> state.forAttempt(event.attempt) {
        if (status == Preparing || status is Recording || status == Processing) {
            copy(status = Failed(event.reason))
        } else {
            this
        }
    }
}

private inline fun CaptureState.forAttempt(
    attempt: Int,
    transition: CaptureState.() -> CaptureState,
): CaptureState = if (attempt == this.attempt) transition() else this
