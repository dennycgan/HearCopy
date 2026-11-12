package com.dennycgan.hearcopy.state

import com.dennycgan.hearcopy.state.CaptureEvent.ApiKeyReported
import com.dennycgan.hearcopy.state.CaptureEvent.CaptureFailed
import com.dennycgan.hearcopy.state.CaptureEvent.CaptureSaved
import com.dennycgan.hearcopy.state.CaptureEvent.FirstAudio
import com.dennycgan.hearcopy.state.CaptureEvent.LimitReached
import com.dennycgan.hearcopy.state.CaptureEvent.PermissionsReported
import com.dennycgan.hearcopy.state.CaptureEvent.PreparingTimedOut
import com.dennycgan.hearcopy.state.CaptureEvent.SessionEnded
import com.dennycgan.hearcopy.state.CaptureEvent.SessionLost
import com.dennycgan.hearcopy.state.CaptureEvent.SessionStarted
import com.dennycgan.hearcopy.state.CaptureEvent.TapStart
import com.dennycgan.hearcopy.state.CaptureEvent.TapStop
import com.dennycgan.hearcopy.state.CaptureEvent.TranscriptReady
import com.dennycgan.hearcopy.state.CaptureEvent.TranscriptRestored
import com.dennycgan.hearcopy.state.CaptureEvent.TranscriptionFailed
import com.dennycgan.hearcopy.state.CaptureStatus.Completed
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

    // Status and attempt are untouched: deleting the key ends the session
    // through the service's teardown, which reports SessionEnded.
    is ApiKeyReported -> state.copy(setup = state.setup.copy(apiKeyPresent = event.present))

    SessionStarted -> state.copy(setup = state.setup.copy(projectionActive = true))

    // Bumping the attempt makes every late event of the old session stale.
    SessionEnded -> state.copy(
        setup = state.setup.copy(projectionActive = false),
        status = Ready,
        attempt = state.attempt + 1,
    )

    // The upload in flight keeps its attempt and Processing status, so its
    // outcome still lands; only the session is gone.
    SessionLost -> state.copy(setup = state.setup.copy(projectionActive = false))

    // Gated only on the session: the reported RECORD_AUDIO value may be
    // stale, so the service checks the permission itself at tap time.
    TapStart ->
        if (state.setup.projectionActive && tapAction(state.status) == TapAction.Start) {
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

    // The clip stays in Processing while it is uploaded; the upload's
    // outcome (TranscriptReady or TranscriptionFailed) ends it.
    is CaptureSaved -> state

    is CaptureFailed -> state.forAttempt(event.attempt) {
        if (status == Preparing || status is Recording || status == Processing) {
            copy(status = Failed(event.reason))
        } else {
            this
        }
    }

    // The transcript was written before this event, so it is kept even when
    // the attempt is no longer current (a teardown during the write); only
    // the status change belongs to the attempt.
    is TranscriptReady -> state.copy(lastTranscript = event.text).forAttempt(event.attempt) {
        if (status == Processing) copy(status = Completed) else this
    }

    is TranscriptionFailed -> state.forAttempt(event.attempt) {
        if (status == Processing) copy(status = Failed(event.reason)) else this
    }

    // A transcript received in this process is newer than the stored one.
    is TranscriptRestored -> if (state.lastTranscript == null) state.copy(lastTranscript = event.text) else state
}

/**
 * The session was lost during an upload and the service has not ended yet:
 * between [CaptureEvent.SessionLost] and [CaptureEvent.SessionEnded]. No
 * other state has no session and a status other than Ready: TapStart needs
 * the session, and SessionEnded always returns to Ready.
 */
fun isFinishing(state: CaptureState): Boolean =
    !state.setup.projectionActive && state.status != Ready

private inline fun CaptureState.forAttempt(
    attempt: Int,
    transition: CaptureState.() -> CaptureState,
): CaptureState = if (attempt == this.attempt) transition() else this
