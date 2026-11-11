package com.dennycgan.hearcopy.state

import com.dennycgan.hearcopy.state.CaptureEvent.ApiKeyReported
import com.dennycgan.hearcopy.state.CaptureEvent.CaptureFailed
import com.dennycgan.hearcopy.state.CaptureEvent.CaptureSaved
import com.dennycgan.hearcopy.state.CaptureEvent.FirstAudio
import com.dennycgan.hearcopy.state.CaptureEvent.LimitReached
import com.dennycgan.hearcopy.state.CaptureEvent.PermissionsReported
import com.dennycgan.hearcopy.state.CaptureEvent.PreparingTimedOut
import com.dennycgan.hearcopy.state.CaptureEvent.SessionEnded
import com.dennycgan.hearcopy.state.CaptureEvent.SessionStarted
import com.dennycgan.hearcopy.state.CaptureEvent.TapStart
import com.dennycgan.hearcopy.state.CaptureEvent.TapStop
import com.dennycgan.hearcopy.state.CaptureStatus.Failed
import com.dennycgan.hearcopy.state.CaptureStatus.Preparing
import com.dennycgan.hearcopy.state.CaptureStatus.Processing
import com.dennycgan.hearcopy.state.CaptureStatus.Ready
import com.dennycgan.hearcopy.state.CaptureStatus.Recording
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ReduceTest {

    private val session = Setup(overlayGranted = true, recordAudioGranted = true, projectionActive = true)

    private fun state(status: CaptureStatus, attempt: Int = 1, setup: Setup = session) =
        CaptureState(setup = setup, status = status, attempt = attempt)

    private val allStatuses = listOf(
        Ready, Preparing, Recording(startedAtMs = 10), Processing, Failed(FailureReason.ReadError),
    )

    // Setup events

    @Test
    fun `initial state is Ready with nothing granted`() {
        assertEquals(CaptureState(Setup(false, false, false), Ready, 0), CaptureState())
    }

    @Test
    fun `PermissionsReported sets only the two permission fields`() {
        val before = state(Recording(10), setup = Setup(false, false, projectionActive = true))
        val after = reduce(before, PermissionsReported(overlayGranted = true, recordAudioGranted = false))
        assertEquals(before.copy(setup = Setup(true, false, projectionActive = true)), after)
    }

    @Test
    fun `PermissionsReported never clears projectionActive`() {
        val before = state(Ready, setup = Setup(true, true, projectionActive = true))
        val after = reduce(before, PermissionsReported(overlayGranted = false, recordAudioGranted = false))
        assertEquals(true, after.setup.projectionActive)
    }

    @Test
    fun `SessionStarted sets only projectionActive`() {
        val before = state(Ready, attempt = 4, setup = Setup(true, false, projectionActive = false))
        val after = reduce(before, SessionStarted)
        assertEquals(before.copy(setup = Setup(true, false, projectionActive = true)), after)
    }

    @Test
    fun `SessionEnded from any status clears the session, returns to Ready and bumps the attempt`() {
        allStatuses.forEach { status ->
            val after = reduce(state(status, attempt = 3), SessionEnded)
            assertEquals(
                "from $status",
                CaptureState(Setup(true, true, projectionActive = false), Ready, attempt = 4),
                after,
            )
        }
    }

    @Test
    fun `events of the previous session are dropped after SessionEnded`() {
        val ended = reduce(state(Recording(10), attempt = 3), SessionEnded)
        val restarted = reduce(ended, SessionStarted)
        listOf(
            FirstAudio(3, 20),
            LimitReached(3),
            PreparingTimedOut(3),
            CaptureSaved(3),
            CaptureFailed(3, FailureReason.NoAudio),
        ).forEach { assertSame("$it", restarted, reduce(restarted, it)) }
    }

    // Taps

    @Test
    fun `TapStart from Ready enters Preparing with a new attempt`() {
        assertEquals(state(Preparing, attempt = 2), reduce(state(Ready, attempt = 1), TapStart))
    }

    @Test
    fun `TapStart from Failed retries without a restart`() {
        val failed = state(Failed(FailureReason.PreparingTimeout), attempt = 5)
        assertEquals(state(Preparing, attempt = 6), reduce(failed, TapStart))
    }

    @Test
    fun `TapStart without an active session is ignored`() {
        val noSession = session.copy(projectionActive = false)
        listOf(Ready, Failed(FailureReason.NoAudio)).forEach { status ->
            val before = state(status, setup = noSession)
            assertSame("from $status", before, reduce(before, TapStart))
        }
    }

    @Test
    fun `TapStart does not depend on the reported RECORD_AUDIO value`() {
        val stale = session.copy(recordAudioGranted = false)
        assertEquals(Preparing, reduce(state(Ready, setup = stale), TapStart).status)
    }

    @Test
    fun `TapStart is ignored while Preparing, Recording or Processing`() {
        listOf(Preparing, Recording(10), Processing).forEach { status ->
            val before = state(status)
            assertSame("from $status", before, reduce(before, TapStart))
        }
    }

    @Test
    fun `TapStop from Recording enters Processing`() {
        assertEquals(state(Processing), reduce(state(Recording(10)), TapStop(atMs = 10 + MIN_CLIP_MS)))
    }

    @Test
    fun `TapStop outside Recording is ignored, including Preparing`() {
        listOf(Ready, Preparing, Processing, Failed(FailureReason.ReadError)).forEach { status ->
            val before = state(status)
            assertSame("from $status", before, reduce(before, TapStop(atMs = 5_000)))
        }
    }

    // Minimum clip length

    @Test
    fun `the minimum clip length is one second`() {
        assertEquals(1_000L, MIN_CLIP_MS)
    }

    @Test
    fun `TapStop exactly MIN_CLIP_MS after first audio keeps the clip`() {
        assertEquals(state(Processing), reduce(state(Recording(500)), TapStop(atMs = 500 + MIN_CLIP_MS)))
    }

    @Test
    fun `TapStop before MIN_CLIP_MS discards the clip and returns to Ready without failing`() {
        listOf(500L, 600L, 500 + MIN_CLIP_MS - 1).forEach { atMs ->
            val before = state(Recording(500), attempt = 3)
            assertEquals("stop at $atMs", before.copy(status = Ready), reduce(before, TapStop(atMs)))
        }
    }

    @Test
    fun `late events of a discarded clip leave Ready unchanged`() {
        val discarded = reduce(state(Recording(500), attempt = 3), TapStop(atMs = 900))
        listOf(
            CaptureSaved(3),
            CaptureFailed(3, FailureReason.ReadError),
            LimitReached(3),
            FirstAudio(3, 950),
            PreparingTimedOut(3),
            TapStop(atMs = 5_000),
        ).forEach { event ->
            assertSame("$event", discarded, reduce(discarded, event))
        }
    }

    @Test
    fun `a new recording starts right after a discarded clip`() {
        val discarded = reduce(state(Recording(500), attempt = 3), TapStop(atMs = 900))
        assertEquals(state(Preparing, attempt = 4), reduce(discarded, TapStart))
    }
    // Capture progress

    @Test
    fun `FirstAudio moves Preparing to Recording with the capture start time`() {
        assertEquals(state(Recording(startedAtMs = 1234)), reduce(state(Preparing), FirstAudio(1, 1234)))
    }

    @Test
    fun `FirstAudio outside Preparing is ignored`() {
        listOf(Ready, Recording(10), Processing, Failed(FailureReason.ReadError)).forEach { status ->
            val before = state(status)
            assertSame("from $status", before, reduce(before, FirstAudio(1, 99)))
        }
    }

    @Test
    fun `LimitReached stops Recording like a manual stop`() {
        assertEquals(state(Processing), reduce(state(Recording(10)), LimitReached(1)))
    }

    @Test
    fun `LimitReached outside Recording is ignored`() {
        listOf(Ready, Preparing, Processing, Failed(FailureReason.ReadError)).forEach { status ->
            val before = state(status)
            assertSame("from $status", before, reduce(before, LimitReached(1)))
        }
    }

    @Test
    fun `PreparingTimedOut moves Preparing to Failed`() {
        assertEquals(
            state(Failed(FailureReason.PreparingTimeout)),
            reduce(state(Preparing), PreparingTimedOut(1)),
        )
    }

    @Test
    fun `PreparingTimedOut after first audio is ignored`() {
        listOf(Ready, Recording(10), Processing, Failed(FailureReason.ReadError)).forEach { status ->
            val before = state(status)
            assertSame("from $status", before, reduce(before, PreparingTimedOut(1)))
        }
    }

    @Test
    fun `CaptureSaved moves Processing to Ready`() {
        assertEquals(state(Ready), reduce(state(Processing), CaptureSaved(1)))
    }

    @Test
    fun `CaptureSaved outside Processing is ignored`() {
        listOf(Ready, Preparing, Recording(10), Failed(FailureReason.ReadError)).forEach { status ->
            val before = state(status)
            assertSame("from $status", before, reduce(before, CaptureSaved(1)))
        }
    }

    @Test
    fun `CaptureFailed fails Preparing, Recording and Processing with its reason`() {
        mapOf(
            Preparing to FailureReason.InitFailed,
            Recording(10) to FailureReason.ReadError,
            Processing to FailureReason.NoAudio,
        ).forEach { (status, reason) ->
            assertEquals("from $status", state(Failed(reason)), reduce(state(status), CaptureFailed(1, reason)))
        }
    }

    @Test
    fun `CaptureFailed in Ready or Failed is ignored`() {
        listOf(Ready, Failed(FailureReason.PreparingTimeout)).forEach { status ->
            val before = state(status)
            assertSame("from $status", before, reduce(before, CaptureFailed(1, FailureReason.ReadError)))
        }
    }

    // Stale attempts

    @Test
    fun `events of another attempt are dropped`() {
        mapOf(
            Preparing to FirstAudio(attempt = 1, atMs = 5),
            Recording(10) to LimitReached(attempt = 1),
            Preparing to PreparingTimedOut(attempt = 1),
            Processing to CaptureSaved(attempt = 1),
            Processing to CaptureFailed(attempt = 1, reason = FailureReason.NoAudio),
        ).forEach { (status, event) ->
            val before = state(status, attempt = 2)
            assertSame("$event in $status", before, reduce(before, event))
        }
    }

    @Test
    fun `a timeout of a failed attempt cannot fail the retry`() {
        val timedOut = reduce(state(Preparing, attempt = 1), PreparingTimedOut(1))
        val retry = reduce(timedOut, TapStart)
        assertSame(retry, reduce(retry, PreparingTimedOut(1)))
        assertEquals(state(Preparing, attempt = 2), retry)
    }

    // API key

    @Test
    fun `the API key is unknown until storage has been read`() {
        assertEquals(null, CaptureState().setup.apiKeyPresent)
    }

    @Test
    fun `ApiKeyReported sets only apiKeyPresent, in every status`() {
        for (status in allStatuses) {
            for (present in listOf(true, false)) {
                val before = state(status, attempt = 3)
                val after = reduce(before, ApiKeyReported(present))
                assertEquals("$status $present", before.copy(setup = session.copy(apiKeyPresent = present)), after)
            }
        }
    }

    @Test
    fun `no other setup event changes apiKeyPresent`() {
        for (present in listOf(null, true, false)) {
            val setup = session.copy(apiKeyPresent = present)
            val events = listOf(PermissionsReported(false, false), SessionStarted, SessionEnded)
            for (event in events) {
                assertEquals("$event", present, reduce(state(Ready, setup = setup), event).setup.apiKeyPresent)
            }
        }
    }

    // Full flows

    @Test
    fun `one session allows several recordings in a row`() {
        var s = reduce(CaptureState(), PermissionsReported(overlayGranted = true, recordAudioGranted = true))
        s = reduce(s, SessionStarted)
        repeat(3) { i ->
            s = reduce(s, TapStart)
            val attempt = s.attempt
            s = reduce(s, FirstAudio(attempt, atMs = i * 1000L))
            s = reduce(s, TapStop(atMs = i * 1000L + MIN_CLIP_MS))
            s = reduce(s, CaptureSaved(attempt))
            assertEquals("recording ${i + 1}", Ready, s.status)
        }
        assertEquals(3, s.attempt)
        assertEquals(true, s.setup.projectionActive)
    }
}
