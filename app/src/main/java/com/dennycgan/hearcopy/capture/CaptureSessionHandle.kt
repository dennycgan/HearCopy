package com.dennycgan.hearcopy.capture

/**
 * The running capture service as seen by [CaptureCommands]. Set by the
 * service in `onCreate`, cleared in `onDestroy`; main thread only.
 */
interface CaptureSessionHandle {
    fun onTap()
}
