package com.dennycgan.hearcopy.ui

import com.dennycgan.hearcopy.R
import com.dennycgan.hearcopy.state.FailureReason
import com.dennycgan.hearcopy.state.KeyTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Settings Connection block (ADR-019). */
class ConnectionStatusTest {

    @Test
    fun `without a key the test cannot run`() {
        listOf(KeyTest.NotTested, KeyTest.Running, KeyTest.Passed, KeyTest.Failed(FailureReason.Network)).forEach {
            assertEquals("$it", ConnectionStatus(R.string.key_test_not_configured, canTest = false), connectionStatus(false, it))
        }
    }

    @Test
    fun `an unreadable key says so until the next reset`() {
        val expected = ConnectionStatus(R.string.key_test_unreadable, canTest = false, error = true)
        assertEquals(expected, connectionStatus(false, KeyTest.Unreadable))
        assertEquals(expected.copy(canTest = true), connectionStatus(true, KeyTest.Unreadable))
    }

    @Test
    fun `with a key each result has its line`() {
        assertEquals(ConnectionStatus(R.string.key_test_not_tested, canTest = true), connectionStatus(true, KeyTest.NotTested))
        assertEquals(ConnectionStatus(R.string.key_test_running, canTest = false), connectionStatus(true, KeyTest.Running))
        assertEquals(ConnectionStatus(R.string.key_test_passed, canTest = true), connectionStatus(true, KeyTest.Passed))
    }

    @Test
    fun `a failed test adds the reason's text`() {
        FailureReason.entries.forEach { reason ->
            assertEquals(
                "$reason",
                ConnectionStatus(R.string.key_test_failed, reason = failureStatusRes(reason), canTest = true, error = true),
                connectionStatus(true, KeyTest.Failed(reason)),
            )
        }
    }
}
