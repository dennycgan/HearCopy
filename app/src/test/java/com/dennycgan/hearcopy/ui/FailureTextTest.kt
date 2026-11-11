package com.dennycgan.hearcopy.ui

import com.dennycgan.hearcopy.capture.failureTextRes
import com.dennycgan.hearcopy.state.FailureReason
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The failure notification (capture/) and the main screen's status line
 * (ui/) show the same fixed string for every reason. Two mappings, because
 * ui/ may not use capture/ beyond CaptureCommands.
 */
class FailureTextTest {

    @Test
    fun `notification and status line use the same text for every reason`() {
        FailureReason.entries.forEach { reason ->
            assertEquals("$reason", failureStatusRes(reason), failureTextRes(reason))
        }
    }

    @Test
    fun `every reason has its own text`() {
        val texts = FailureReason.entries.map(::failureStatusRes)
        assertEquals(texts.size, texts.toSet().size)
    }
}
