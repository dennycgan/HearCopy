package com.dennycgan.hearcopy.capture

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
}
