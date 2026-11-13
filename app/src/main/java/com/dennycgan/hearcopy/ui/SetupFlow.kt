package com.dennycgan.hearcopy.ui

import androidx.annotation.StringRes
import com.dennycgan.hearcopy.R
import com.dennycgan.hearcopy.state.CaptureState
import com.dennycgan.hearcopy.state.CaptureStatus
import com.dennycgan.hearcopy.state.FailureReason
import com.dennycgan.hearcopy.state.Setup
import com.dennycgan.hearcopy.state.isFinishing

/** The two screens of the single Activity (ADR-018). */
enum class Screen { Main, Settings }

/**
 * Setup steps in auto-advance order. [Checking] and [ApiKey] open no
 * system screen: the first waits for storage, the second for the key on
 * the Settings page.
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

enum class CardIcon { Spinner, Alert, Check, Stop }

/** What a tap on the status card does. */
enum class CardTap {
    None,
    OpenSettings,

    /** Clears declined and opens the next setup step. */
    RetryStep,

    /** Android no longer shows the microphone dialog: HearCopy's app info. */
    OpenAppInfo,
}

/**
 * The status card's content. [tryAgain] appends "Tap to try again." to
 * [description]; [recordingSinceMs] is set only for Recording, whose
 * description takes the elapsed time.
 */
data class StatusCard(
    val icon: CardIcon,
    @StringRes val title: Int,
    @StringRes val description: Int? = null,
    val tryAgain: Boolean = false,
    val recordingSinceMs: Long? = null,
    val tap: CardTap = CardTap.None,
)

/**
 * The status card, first match wins: storage not read, session starting,
 * finishing the last upload (ADR-015; never tappable), a missing setup
 * step, then the capture status. [microphoneBlocked]: the last microphone
 * request was denied and Android no longer asks.
 */
fun statusCard(
    state: CaptureState,
    awaitingSession: Boolean,
    declined: Boolean,
    microphoneBlocked: Boolean,
): StatusCard {
    val step = nextStep(state.setup)
    return when {
        step == SetupStep.Checking -> StatusCard(CardIcon.Spinner, R.string.status_checking_title)
        awaitingSession -> StatusCard(CardIcon.Spinner, R.string.status_starting_title, R.string.status_starting)
        isFinishing(state) -> StatusCard(CardIcon.Spinner, R.string.status_finishing_title, R.string.status_finishing)
        step == SetupStep.ApiKey -> setupCard(R.string.setup_api_key_text, tryAgain = false, CardTap.OpenSettings)
        step == SetupStep.Overlay -> setupCard(R.string.setup_overlay_text, declined, CardTap.RetryStep)
        step == SetupStep.Microphone && microphoneBlocked ->
            setupCard(R.string.setup_microphone_blocked_text, tryAgain = false, CardTap.OpenAppInfo)
        step == SetupStep.Microphone -> setupCard(R.string.setup_microphone_text, declined, CardTap.RetryStep)
        step == SetupStep.Consent -> setupCard(R.string.setup_capture_text, declined, CardTap.RetryStep)
        else -> captureCard(state.status)
    }
}

private fun setupCard(@StringRes text: Int, tryAgain: Boolean, tap: CardTap) =
    StatusCard(CardIcon.Alert, R.string.status_setup_title, text, tryAgain = tryAgain, tap = tap)

/** Preparing shows as Ready (product spec). Failed is not tappable in 3.2. */
private fun captureCard(status: CaptureStatus): StatusCard = when (status) {
    CaptureStatus.Ready, CaptureStatus.Preparing ->
        StatusCard(CardIcon.Check, R.string.status_ready_title, R.string.status_ready)
    is CaptureStatus.Recording ->
        StatusCard(CardIcon.Stop, R.string.status_recording_title, R.string.status_recording, recordingSinceMs = status.startedAtMs)
    CaptureStatus.Processing -> StatusCard(CardIcon.Spinner, R.string.status_processing_title, R.string.status_processing)
    CaptureStatus.Completed -> StatusCard(CardIcon.Check, R.string.status_completed_title, R.string.status_completed)
    is CaptureStatus.Failed -> StatusCard(CardIcon.Alert, R.string.status_failed_title, failureStatusRes(status.reason))
}

/** Whole seconds recorded after [elapsedMs]; never negative. */
fun recordedSeconds(elapsedMs: Long): Long = elapsedMs.coerceAtLeast(0) / 1000

/** Milliseconds until the next whole second of [elapsedMs]. */
fun msToNextSecond(elapsedMs: Long): Long = 1000 - elapsedMs.coerceAtLeast(0) % 1000

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
