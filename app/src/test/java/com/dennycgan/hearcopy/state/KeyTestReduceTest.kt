package com.dennycgan.hearcopy.state

import com.dennycgan.hearcopy.state.CaptureEvent.KeyTestCancelled
import com.dennycgan.hearcopy.state.CaptureEvent.KeyTestFinished
import com.dennycgan.hearcopy.state.CaptureEvent.KeyTestReset
import com.dennycgan.hearcopy.state.CaptureEvent.KeyTestStarted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Settings key test in the state store (ADR-019). */
class KeyTestReduceTest {

    private val finished = listOf(KeyTest.Passed, KeyTest.Failed(FailureReason.Unauthorized), KeyTest.Unreadable)

    private fun state(keyTest: KeyTest, run: Int = 3) = CaptureState(keyTest = keyTest, keyTestRun = run)

    @Test
    fun `a new state has not been tested`() {
        assertEquals(KeyTest.NotTested, CaptureState().keyTest)
        assertEquals(0, CaptureState().keyTestRun)
    }

    @Test
    fun `KeyTestStarted runs a new run from NotTested or any result`() {
        (listOf(KeyTest.NotTested) + finished).forEach { from ->
            assertEquals("from $from", state(KeyTest.Running, run = 4), reduce(state(from), KeyTestStarted))
        }
    }

    @Test
    fun `KeyTestStarted is ignored while a run is running`() {
        val running = state(KeyTest.Running)
        assertSame(running, reduce(running, KeyTestStarted))
    }

    @Test
    fun `KeyTestFinished applies only to the running run`() {
        finished.forEach { outcome ->
            assertEquals("$outcome", state(outcome), reduce(state(KeyTest.Running), KeyTestFinished(3, outcome)))
        }
    }

    @Test
    fun `KeyTestFinished of another run or with no run running is dropped`() {
        val running = state(KeyTest.Running)
        assertSame(running, reduce(running, KeyTestFinished(2, KeyTest.Passed)))
        (listOf(KeyTest.NotTested) + finished).forEach { from ->
            val before = state(from)
            assertSame("from $from", before, reduce(before, KeyTestFinished(3, KeyTest.Passed)))
        }
    }

    @Test
    fun `KeyTestReset always returns to NotTested with a new run`() {
        (listOf(KeyTest.NotTested, KeyTest.Running) + finished).forEach { from ->
            assertEquals("from $from", state(KeyTest.NotTested, run = 4), reduce(state(from), KeyTestReset))
        }
    }

    @Test
    fun `a result after a reset is dropped`() {
        val reset = reduce(state(KeyTest.Running), KeyTestReset)
        val restarted = reduce(reset, KeyTestStarted)
        assertEquals(5, restarted.keyTestRun)
        assertSame(restarted, reduce(restarted, KeyTestFinished(3, KeyTest.Passed)))
    }

    @Test
    fun `KeyTestCancelled stops only the running run and keeps a result`() {
        assertEquals(state(KeyTest.NotTested), reduce(state(KeyTest.Running), KeyTestCancelled(3)))
        val running = state(KeyTest.Running)
        assertSame(running, reduce(running, KeyTestCancelled(2)))
        finished.forEach { result ->
            val before = state(result)
            assertSame("$result", before, reduce(before, KeyTestCancelled(3)))
        }
    }

    @Test
    fun `no other event touches the key test`() {
        val before = state(KeyTest.Failed(FailureReason.Network)).copy(setup = Setup(projectionActive = true))
        listOf(
            CaptureEvent.ApiKeyReported(true),
            CaptureEvent.ApiKeyReported(false),
            CaptureEvent.SessionStarted,
            CaptureEvent.SessionLost,
            CaptureEvent.SessionEnded,
            CaptureEvent.TapStart,
            CaptureEvent.TranscriptReady(1, "text"),
            CaptureEvent.PermissionsReported(overlayGranted = true, recordAudioGranted = true),
        ).forEach { event ->
            val after = reduce(before, event)
            assertEquals("$event", before.keyTest, after.keyTest)
            assertEquals("$event", before.keyTestRun, after.keyTestRun)
        }
    }

    @Test
    fun `the state prints the key test`() {
        val printed = state(KeyTest.Failed(FailureReason.Unauthorized)).toString()
        assertTrue(printed, printed.contains("keyTest=Failed(reason=Unauthorized)"))
        assertTrue(printed, printed.contains("keyTestRun=3"))
    }
}
