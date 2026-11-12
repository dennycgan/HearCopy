package com.dennycgan.hearcopy.capture

import com.dennycgan.hearcopy.capture.CaptureService.EndingPlan
import com.dennycgan.hearcopy.capture.CaptureService.Phase
import com.dennycgan.hearcopy.capture.CaptureService.TeardownReason
import org.junit.Assert.assertEquals
import org.junit.Test

class TeardownReasonTest {

    @Test
    fun `only endings the user did not cause post the alert`() {
        val expected = mapOf(
            TeardownReason.UserEnded to false,
            TeardownReason.ProjectionLost to true,
            TeardownReason.PermissionLost to true,
            TeardownReason.CaptureLost to true,
            TeardownReason.Internal to false,
            TeardownReason.KeyRemoved to false,
            TeardownReason.KeyUnreadable to true,
        )
        assertEquals(TeardownReason.entries.toSet(), expected.keys)
        expected.forEach { (reason, alert) -> assertEquals("for $reason", alert, reason.postsAlert) }
    }

    @Test
    fun `only a lost authorisation lets the upload in flight finish`() {
        val expected = mapOf(
            TeardownReason.UserEnded to false,
            TeardownReason.ProjectionLost to true,
            TeardownReason.PermissionLost to true,
            TeardownReason.CaptureLost to true,
            TeardownReason.Internal to false,
            TeardownReason.KeyRemoved to false,
            TeardownReason.KeyUnreadable to false,
        )
        assertEquals(TeardownReason.entries.toSet(), expected.keys)
        expected.forEach { (reason, defers) -> assertEquals("for $reason", defers, reason.defersUpload) }
    }

    @Test
    fun `a live session defers only for a lost authorisation with an upload in flight`() {
        TeardownReason.entries.forEach { reason ->
            assertEquals("$reason, no upload", EndingPlan.StopNow, reason.planFor(Phase.Live, uploadInFlight = false))
            val withUpload = if (reason.defersUpload) EndingPlan.Defer else EndingPlan.StopNow
            assertEquals("$reason, upload", withUpload, reason.planFor(Phase.Live, uploadInFlight = true))
        }
    }

    @Test
    fun `in the deferred window Recents, a key delete or onDestroy stop at once`() {
        val stops = setOf(
            TeardownReason.UserEnded,
            TeardownReason.KeyRemoved,
            TeardownReason.Internal,
            TeardownReason.KeyUnreadable,
        )
        TeardownReason.entries.forEach { reason ->
            val expected = if (reason in stops) EndingPlan.StopNow else EndingPlan.Ignore
            assertEquals("$reason", expected, reason.planFor(Phase.Deferred, uploadInFlight = true))
        }
    }

    @Test
    fun `an ended service ignores every later ending`() {
        TeardownReason.entries.forEach { reason ->
            listOf(true, false).forEach { upload ->
                assertEquals("$reason, upload=$upload", EndingPlan.Ignore, reason.planFor(Phase.Ended, upload))
            }
        }
    }
}
