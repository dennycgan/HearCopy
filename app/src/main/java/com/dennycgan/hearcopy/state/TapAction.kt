package com.dennycgan.hearcopy.state

enum class TapAction {
    Start,
    Stop,

    /** Tap has no effect and no feedback (Preparing). */
    Ignore,

    /** Tap has no effect; the overlay gives haptic feedback (Processing). */
    Reject,
}

/**
 * What a tap on hearcopyTouch means in [status]. The service decides with
 * the store's current value; the overlay uses it only to pick feedback.
 */
fun tapAction(status: CaptureStatus): TapAction = when (status) {
    CaptureStatus.Ready -> TapAction.Start
    CaptureStatus.Completed -> TapAction.Start
    is CaptureStatus.Failed -> TapAction.Start
    CaptureStatus.Preparing -> TapAction.Ignore
    is CaptureStatus.Recording -> TapAction.Stop
    CaptureStatus.Processing -> TapAction.Reject
}
