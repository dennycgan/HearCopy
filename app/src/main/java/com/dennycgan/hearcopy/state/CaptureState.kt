package com.dennycgan.hearcopy.state

/**
 * The single capture state of the app. Written only by capture/, read by
 * the service, the overlay and the main screen.
 *
 * [attempt] identifies the current recording attempt. Asynchronous events
 * carry the attempt they belong to; events of any other attempt are dropped.
 */
data class CaptureState(
    val setup: Setup = Setup(),
    val status: CaptureStatus = CaptureStatus.Ready,
    val attempt: Int = 0,
)

/**
 * Start conditions. Each field has exactly one writer event, so no writer
 * can overwrite a field it does not own.
 */
data class Setup(
    val overlayGranted: Boolean = false,
    val recordAudioGranted: Boolean = false,
    val projectionActive: Boolean = false,
    /** Whether an API key is stored; null until storage has been read. */
    val apiKeyPresent: Boolean? = null,
)

sealed interface CaptureStatus {
    data object Ready : CaptureStatus

    /** Tapped; waiting for the first successful read. Button stays white. */
    data object Preparing : CaptureStatus

    /** First audio arrived at [startedAtMs] on the monotonic clock. */
    data class Recording(val startedAtMs: Long) : CaptureStatus

    /** Recording stopped; the clip is being finalised. */
    data object Processing : CaptureStatus

    data class Failed(val reason: FailureReason) : CaptureStatus
}

enum class FailureReason {
    PreparingTimeout,
    InitFailed,
    ReadError,
    NoAudio,
}
