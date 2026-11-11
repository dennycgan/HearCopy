package com.dennycgan.hearcopy.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TapActionTest {

    @Test
    fun `tap action for every status`() {
        val expected = mapOf(
            CaptureStatus.Ready to TapAction.Start,
            CaptureStatus.Failed(FailureReason.PreparingTimeout) to TapAction.Start,
            CaptureStatus.Failed(FailureReason.NoAudio) to TapAction.Start,
            CaptureStatus.Preparing to TapAction.Ignore,
            CaptureStatus.Recording(startedAtMs = 42) to TapAction.Stop,
            CaptureStatus.Processing to TapAction.Reject,
        )
        expected.forEach { (status, action) -> assertEquals("for $status", action, tapAction(status)) }
    }

    @Test
    fun `a tap in Completed starts a new recording, as reduce agrees`() {
        assertEquals(TapAction.Start, tapAction(CaptureStatus.Completed))
        val session = Setup(overlayGranted = true, recordAudioGranted = true, projectionActive = true)
        val before = CaptureState(session, CaptureStatus.Completed, attempt = 1)
        assertEquals(CaptureStatus.Preparing, reduce(before, CaptureEvent.TapStart).status)
    }

    @Test
    fun `tap action agrees with reduce`() {
        val session = Setup(overlayGranted = true, recordAudioGranted = true, projectionActive = true)
        listOf(
            CaptureStatus.Ready,
            CaptureStatus.Failed(FailureReason.ReadError),
            CaptureStatus.Preparing,
            CaptureStatus.Recording(startedAtMs = 1),
            CaptureStatus.Processing,
        ).forEach { status ->
            val before = CaptureState(session, status, attempt = 1)
            val event = when (tapAction(status)) {
                TapAction.Start -> CaptureEvent.TapStart
                TapAction.Stop -> CaptureEvent.TapStop(atMs = 1 + MIN_CLIP_MS)
                TapAction.Ignore, TapAction.Reject -> null
            }
            if (event == null) {
                listOf(CaptureEvent.TapStart, CaptureEvent.TapStop(atMs = 1 + MIN_CLIP_MS)).forEach {
                    assertEquals("$it in $status", before, reduce(before, it))
                }
            } else {
                assertNotEquals("$event in $status changes state", before, reduce(before, event))
            }
        }
    }
}
