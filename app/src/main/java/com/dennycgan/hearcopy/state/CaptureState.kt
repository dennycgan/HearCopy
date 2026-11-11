package com.dennycgan.hearcopy.state

/**
 * The single capture state of the app. Written only by capture/, read by
 * the service, the overlay and the main screen.
 *
 * [attempt] identifies the current recording attempt. Asynchronous events
 * carry the attempt they belong to; events of any other attempt are dropped.
 *
 * [lastTranscript] is the last transcript that was received, kept apart from
 * [status] so that no failure, cancel or new recording clears it.
 */
data class CaptureState(
    val setup: Setup = Setup(),
    val status: CaptureStatus = CaptureStatus.Ready,
    val attempt: Int = 0,
    val lastTranscript: String? = null,
) {
    /** Shows only the transcript's length, so the text cannot reach a log. */
    override fun toString(): String =
        "CaptureState(setup=$setup, status=$status, attempt=$attempt, " +
            "lastTranscript=${lastTranscript?.let { "length=${it.length}" }})"
}

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

    /** Recording stopped; the clip is being finalised and transcribed. */
    data object Processing : CaptureStatus

    /** The last attempt produced a transcript. A tap starts a new recording. */
    data object Completed : CaptureStatus

    data class Failed(val reason: FailureReason) : CaptureStatus
}

enum class FailureReason {
    PreparingTimeout,
    InitFailed,
    ReadError,
    NoAudio,

    /** The transcript was blank. */
    NoSpeech,

    /** HTTP 401, or a stored key the client refused to send. */
    Unauthorized,

    /** HTTP 403. */
    Forbidden,

    /** HTTP 429 without a billing signal. */
    RateLimited,

    /** HTTP 429 with a billing signal. */
    QuotaExceeded,

    /** The call timeout fired, or HTTP 408. */
    Timeout,

    /** The connection failed. */
    Network,

    /** HTTP 5xx. */
    Server,

    /** Any other non-2xx status. */
    Rejected,

    /** A 2xx response without a transcript. */
    Malformed,

    /** The clip is larger than the upload limit. */
    TooLarge,

    /** The stored key could not be read this time; nothing was deleted. */
    KeyUnavailable,
}
