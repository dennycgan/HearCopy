package com.dennycgan.hearcopy.state

sealed interface CaptureEvent {
    /** Permission facts observed by the main screen. Sets only those two fields. */
    data class PermissionsReported(
        val overlayGranted: Boolean,
        val recordAudioGranted: Boolean,
    ) : CaptureEvent

    /** Whether an API key is stored. Sets only apiKeyPresent. */
    data class ApiKeyReported(val present: Boolean) : CaptureEvent

    /** The service holds a valid capture consent. Sets only projectionActive. */
    data object SessionStarted : CaptureEvent

    /** The capture session is over; anything in flight is cancelled. */
    data object SessionEnded : CaptureEvent

    data object TapStart : CaptureEvent

    /**
     * Manual stop at [atMs], on the monotonic clock of the overlay timer
     * (the clock of [CaptureStatus.Recording.startedAtMs]).
     */
    data class TapStop(val atMs: Long) : CaptureEvent

    data class FirstAudio(val attempt: Int, val atMs: Long) : CaptureEvent

    /** The 300 s cap was reached (sample count or monotonic clock). */
    data class LimitReached(val attempt: Int) : CaptureEvent

    data class PreparingTimedOut(val attempt: Int) : CaptureEvent

    /** The clip is valid and finalised; the attempt stays in Processing while it is uploaded. */
    data class CaptureSaved(val attempt: Int) : CaptureEvent

    data class CaptureFailed(val attempt: Int, val reason: FailureReason) : CaptureEvent

    /**
     * The transcript of [attempt] has been written to storage (or the write
     * was tried). [toString] shows only the length.
     */
    class TranscriptReady(val attempt: Int, val text: String) : CaptureEvent {
        override fun equals(other: Any?): Boolean =
            other is TranscriptReady && other.attempt == attempt && other.text == text

        override fun hashCode(): Int = 31 * attempt + text.hashCode()

        override fun toString(): String = "TranscriptReady(attempt=$attempt, length=${text.length})"
    }

    /** The upload of [attempt] produced no transcript. */
    data class TranscriptionFailed(val attempt: Int, val reason: FailureReason) : CaptureEvent

    /** The stored transcript, read at process start. [toString] shows only the length. */
    class TranscriptRestored(val text: String) : CaptureEvent {
        override fun equals(other: Any?): Boolean = other is TranscriptRestored && other.text == text

        override fun hashCode(): Int = text.hashCode()

        override fun toString(): String = "TranscriptRestored(length=${text.length})"
    }
}
