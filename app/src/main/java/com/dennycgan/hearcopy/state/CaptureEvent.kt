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

    /**
     * The capture service has ended. Anything still in flight was cancelled,
     * or (after [SessionLost]) has finished.
     */
    data object SessionEnded : CaptureEvent

    /**
     * Capture authorisation was lost while an upload is in flight. The
     * service stays until the upload ends, then reports [SessionEnded].
     * Sets only projectionActive; the attempt keeps its status so the
     * upload's outcome still applies.
     */
    data object SessionLost : CaptureEvent

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

    /** The stored hearcopyTouch position, read at process start. Applies only when none is set. */
    data class TouchPositionRestored(val position: PanelPosition) : CaptureEvent

    /** hearcopyTouch came to rest after a drag. Always sets the position. */
    data class TouchMoved(val position: PanelPosition) : CaptureEvent
}
