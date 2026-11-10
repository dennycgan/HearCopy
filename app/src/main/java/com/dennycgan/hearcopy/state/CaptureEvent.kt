package com.dennycgan.hearcopy.state

sealed interface CaptureEvent {
    /** Permission facts observed by the main screen. Sets only those two fields. */
    data class PermissionsReported(
        val overlayGranted: Boolean,
        val recordAudioGranted: Boolean,
    ) : CaptureEvent

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

    data class CaptureSaved(val attempt: Int) : CaptureEvent

    data class CaptureFailed(val attempt: Int, val reason: FailureReason) : CaptureEvent
}
