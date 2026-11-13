package com.dennycgan.hearcopy.state

import com.dennycgan.hearcopy.state.CaptureEvent.ApiKeyReported
import com.dennycgan.hearcopy.state.CaptureEvent.CaptureFailed
import com.dennycgan.hearcopy.state.CaptureEvent.CaptureSaved
import com.dennycgan.hearcopy.state.CaptureEvent.FirstAudio
import com.dennycgan.hearcopy.state.CaptureEvent.LimitReached
import com.dennycgan.hearcopy.state.CaptureEvent.PermissionsReported
import com.dennycgan.hearcopy.state.CaptureEvent.PreparingTimedOut
import com.dennycgan.hearcopy.state.CaptureEvent.SessionEnded
import com.dennycgan.hearcopy.state.CaptureEvent.SessionLost
import com.dennycgan.hearcopy.state.CaptureEvent.SessionStarted
import com.dennycgan.hearcopy.state.CaptureEvent.TapStart
import com.dennycgan.hearcopy.state.CaptureEvent.TapStop
import com.dennycgan.hearcopy.state.CaptureEvent.TouchMoved
import com.dennycgan.hearcopy.state.CaptureEvent.TouchPositionRestored
import com.dennycgan.hearcopy.state.CaptureEvent.TranscriptReady
import com.dennycgan.hearcopy.state.CaptureEvent.TranscriptRestored
import com.dennycgan.hearcopy.state.CaptureEvent.TranscriptionFailed
import com.dennycgan.hearcopy.state.CaptureStatus.Completed
import com.dennycgan.hearcopy.state.CaptureStatus.Failed
import com.dennycgan.hearcopy.state.CaptureStatus.Preparing
import com.dennycgan.hearcopy.state.CaptureStatus.Processing
import com.dennycgan.hearcopy.state.CaptureStatus.Ready
import com.dennycgan.hearcopy.state.CaptureStatus.Recording
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    // Rewritten in phase 2.4 (Cap 2 design Q9): was "CaptureSaved moves Processing to Ready".
    @Test
    fun `CaptureSaved keeps Processing while the clip is uploaded`() {
        val before = state(Processing)
        assertSame(before, reduce(before, CaptureSaved(1)))
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
            Preparing to FailureReason.PreparingTimeout,
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
            // Rewritten in phase 2.4 (owner, Q1): was Ready right after CaptureSaved.
            assertEquals("recording ${i + 1} saved", Processing, s.status)
            s = reduce(s, TranscriptReady(attempt, "text ${i + 1}"))
            assertEquals("recording ${i + 1}", Completed, s.status)
            assertEquals("text ${i + 1}", s.lastTranscript)
        }
        assertEquals(3, s.attempt)
        assertEquals(true, s.setup.projectionActive)
    }

    // Transcription (phase 2.4)

    @Test
    fun `TranscriptReady completes its attempt and sets the transcript`() {
        val after = reduce(state(Processing).copy(lastTranscript = "old"), TranscriptReady(1, "new"))
        assertEquals(state(Completed).copy(lastTranscript = "new"), after)
    }

    @Test
    fun `TranscriptReady of a stale attempt sets the transcript but not the status`() {
        // Written to storage before a teardown ended the session.
        val after = reduce(state(Ready, attempt = 2), TranscriptReady(1, "new"))
        assertEquals(state(Ready, attempt = 2).copy(lastTranscript = "new"), after)
    }

    @Test
    fun `TranscriptReady outside Processing sets only the transcript`() {
        listOf(Ready, Preparing, Recording(10), Completed, Failed(FailureReason.Network)).forEach { status ->
            val after = reduce(state(status), TranscriptReady(1, "new"))
            assertEquals("from $status", state(status).copy(lastTranscript = "new"), after)
        }
    }

    @Test
    fun `TranscriptionFailed fails Processing with its reason and keeps the transcript`() {
        val before = state(Processing).copy(lastTranscript = "old")
        FailureReason.entries.forEach { reason ->
            val after = reduce(before, TranscriptionFailed(1, reason))
            assertEquals("$reason", state(Failed(reason)).copy(lastTranscript = "old"), after)
        }
    }

    @Test
    fun `TranscriptionFailed outside Processing or of another attempt is ignored`() {
        listOf(Ready, Preparing, Recording(10), Completed, Failed(FailureReason.ReadError)).forEach { status ->
            val before = state(status)
            assertSame("from $status", before, reduce(before, TranscriptionFailed(1, FailureReason.Network)))
        }
        val stale = state(Processing, attempt = 2)
        assertSame(stale, reduce(stale, TranscriptionFailed(1, FailureReason.Network)))
    }

    @Test
    fun `TranscriptRestored sets only a missing transcript`() {
        assertEquals("stored", reduce(state(Ready), TranscriptRestored("stored")).lastTranscript)
        val received = state(Completed).copy(lastTranscript = "newer")
        assertSame(received, reduce(received, TranscriptRestored("stored")))
    }

    @Test
    fun `TapStart from Completed starts a new recording and keeps the transcript`() {
        val after = reduce(state(Completed).copy(lastTranscript = "old"), TapStart)
        assertEquals(state(Preparing, attempt = 2).copy(lastTranscript = "old"), after)
        val noSession = state(Completed, setup = session.copy(projectionActive = false))
        assertSame(noSession, reduce(noSession, TapStart))
    }

    @Test
    fun `no failure, discard or session end clears the transcript`() {
        val kept = "old"
        listOf(
            state(Processing) to CaptureFailed(1, FailureReason.NoAudio),
            state(Processing) to TranscriptionFailed(1, FailureReason.NoSpeech),
            state(Recording(10)) to TapStop(atMs = 10 + MIN_CLIP_MS - 1),
            state(Processing) to SessionEnded,
            state(Completed) to SessionEnded,
        ).forEach { (before, event) ->
            assertEquals("$event", kept, reduce(before.copy(lastTranscript = kept), event).lastTranscript)
        }
    }

    @Test
    fun `state and transcript events never print the transcript`() {
        val text = "a private sentence"
        val printed = listOf(
            state(Completed).copy(lastTranscript = text).toString(),
            TranscriptReady(1, text).toString(),
            TranscriptRestored(text).toString(),
        )
        printed.forEach { assertFalse(it, it.contains("private")) }
        assertTrue(printed[0].contains("length=${text.length}"))
    }

    // Lost session with an upload in flight (ADR-015)

    @Test
    fun `SessionLost clears only projectionActive and keeps status, attempt and transcript`() {
        allStatuses.forEach { status ->
            val before = state(status, attempt = 3).copy(lastTranscript = "old")
            val after = reduce(before, SessionLost)
            assertEquals("from $status", before.copy(setup = before.setup.copy(projectionActive = false)), after)
        }
    }

    @Test
    fun `SessionLost twice is the same as once`() {
        val once = reduce(state(Processing), SessionLost)
        assertEquals(once, reduce(once, SessionLost))
    }

    @Test
    fun `after SessionLost the upload outcome still applies and taps do nothing`() {
        val lost = reduce(state(Processing, attempt = 3), SessionLost)
        assertSame(lost, reduce(lost, TapStart))
        val ready = reduce(lost, TranscriptReady(3, "new"))
        assertEquals(Completed, ready.status)
        assertEquals("new", ready.lastTranscript)
        assertEquals(Failed(FailureReason.Network), reduce(lost, TranscriptionFailed(3, FailureReason.Network)).status)
    }

    @Test
    fun `SessionEnded after SessionLost returns to Ready and drops late events`() {
        val done = reduce(reduce(state(Processing, attempt = 3), SessionLost), TranscriptReady(3, "new"))
        val ended = reduce(done, SessionEnded)
        assertEquals(CaptureState(session.copy(projectionActive = false), Ready, attempt = 4, lastTranscript = "new"), ended)
        assertSame(ended, reduce(ended, TranscriptionFailed(3, FailureReason.Network)))
    }

    @Test
    fun `finishing lasts from SessionLost until SessionEnded, whatever the upload outcome`() {
        val live = state(Processing, attempt = 3)
        assertFalse(isFinishing(live))
        val lost = reduce(live, SessionLost)
        assertTrue(isFinishing(lost))
        listOf(TranscriptReady(3, "new"), TranscriptionFailed(3, FailureReason.Timeout)).forEach { outcome ->
            val done = reduce(lost, outcome)
            assertTrue("$outcome", isFinishing(done))
            assertFalse("$outcome", isFinishing(reduce(done, SessionEnded)))
        }
        assertFalse(isFinishing(reduce(lost, SessionEnded)))
    }

    @Test
    fun `no state is finishing without a SessionLost`() {
        assertFalse(isFinishing(CaptureState()))
        (allStatuses + Completed).forEach { status ->
            assertFalse("live $status", isFinishing(state(status)))
            assertFalse("ended $status", isFinishing(reduce(state(status), SessionEnded)))
        }
    }

    // hearcopyTouch position (ADR-017)

    @Test
    fun `TouchPositionRestored sets the position when none is set and nothing else`() {
        (allStatuses + Completed).forEach { status ->
            val before = state(status)
            assertEquals("$status", before.copy(touchPosition = PanelPosition(10, 20)), reduce(before, TouchPositionRestored(PanelPosition(10, 20))))
        }
    }

    @Test
    fun `TouchPositionRestored never replaces a position from a drag`() {
        val dragged = reduce(state(Ready), TouchMoved(PanelPosition(1, 2)))
        assertSame(dragged, reduce(dragged, TouchPositionRestored(PanelPosition(10, 20))))
    }

    @Test
    fun `TouchMoved always sets the position and nothing else`() {
        (allStatuses + Completed).forEach { status ->
            val restored = reduce(state(status), TouchPositionRestored(PanelPosition(10, 20)))
            assertEquals("$status", restored.copy(touchPosition = PanelPosition(3, 4)), reduce(restored, TouchMoved(PanelPosition(3, 4))))
        }
    }

    @Test
    fun `session and transcript events keep the position`() {
        val placed = state(Processing, attempt = 3).copy(touchPosition = PanelPosition(5, 6))
        listOf(SessionLost, SessionEnded, TranscriptReady(3, "new"), ApiKeyReported(false)).forEach { event ->
            assertEquals("$event", PanelPosition(5, 6), reduce(placed, event).touchPosition)
        }
    }
}
