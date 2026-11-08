package com.dennycgan.hearcopy.capture

import com.dennycgan.hearcopy.capture.FakeAudioSource.Step
import com.dennycgan.hearcopy.capture.LoopHarness.Companion.IDLE
import com.dennycgan.hearcopy.capture.LoopHarness.Companion.LOUD
import com.dennycgan.hearcopy.capture.LoopHarness.Companion.NO_WATCHDOG
import com.dennycgan.hearcopy.capture.LoopHarness.Companion.awaitBlocking
import com.dennycgan.hearcopy.capture.LoopHarness.Companion.eventually
import com.dennycgan.hearcopy.state.CaptureEvent
import com.dennycgan.hearcopy.state.CaptureStateStore
import com.dennycgan.hearcopy.state.CaptureStatus.Ready
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The session-scoped capture (ADR-005); timing model as in [LoopHarness]. */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureLoopTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val store = CaptureStateStore()
    private val harnesses = mutableListOf<LoopHarness>()

    @After
    fun tearDown() {
        harnesses.forEach { it.shutdown() }
    }

    private fun TestScope.harness(
        first: FakeAudioSource = FakeAudioSource(),
        stuckAfterMs: Long = NO_WATCHDOG,
        start: Boolean = true,
    ): LoopHarness = LoopHarness(this, store, temp.root, first, stuckAfterMs).also {
        harnesses += it
        if (start) it.start()
    }

    private fun TestScope.advance(ms: Long) {
        testScheduler.advanceTimeBy(ms)
        runCurrent()
    }

    private fun samplesOf(file: File): ShortArray {
        val bytes = file.readBytes()
        val data = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(data.remaining()).also { data.get(it) }
    }

    /** Stops [clip] and feeds the read that was in flight. */
    private fun LoopHarness.stopAndSave(clip: LoopHarness.Clip, amplitude: Short = LOUD): CaptureResult {
        clip.session.stop()
        source.samples(1, amplitude)
        return clip.session.result.awaitBlocking()
    }

    // Idle and attach / detach (no pre-roll)

    @Test
    fun `while idle every chunk is skipped, never read, and nothing is written`() = runTest {
        val h = harness()
        h.source.samples(3, IDLE)
        h.source.awaitIdle("skip")

        assertEquals(listOf("start"), h.source.calls.filter { it == "start" || it == "read" })
        assertEquals(4, h.source.calls.count { it == "skip" })
        assertTrue(temp.root.listFiles().orEmpty().isEmpty())
        assertEquals(0, h.source.stopCount.get())
    }

    @Test
    fun `a clip holds only reads that start after the attach (no pre-roll)`() = runTest {
        val h = harness()
        h.source.samples(5, IDLE)
        h.source.awaitIdle("skip")

        // tap() also ends the skip that is in flight at the attach with an IDLE chunk.
        val c = h.tap()
        h.source.samples(2, LOUD)
        h.source.awaitIdle("read")

        assertEquals(CaptureResult.Saved(c.file), h.stopAndSave(c))
        val samples = samplesOf(c.file)
        assertEquals(3 * CaptureSession.CHUNK_SAMPLES, samples.size)
        assertTrue(samples.all { it == LOUD })
    }

    @Test
    fun `after a detach the reader skips again and the clip gets nothing more`() = runTest {
        val h = harness()
        val c = h.tap()
        h.source.samples(1, LOUD)
        h.source.awaitIdle("read")
        assertEquals(CaptureResult.Saved(c.file), h.stopAndSave(c))
        h.source.awaitIdle("skip")
        val length = c.file.length()
        val readsBefore = h.source.calls.count { it == "read" }

        h.source.samples(3, LOUD)
        h.source.awaitIdle("skip")

        assertEquals(length, c.file.length())
        assertEquals(readsBefore, h.source.calls.count { it == "read" })
    }

    @Test
    fun `a second attach while a clip is attached is refused`() = runTest {
        val h = harness()
        h.tap()
        val other = CaptureSession(99, File(temp.root, "other.wav"), store, backgroundScope, { 0L }, h.logs::add)

        assertFalse(h.loop.attach(other))
    }

    @Test
    fun `many clips on one source - started once, never stopped or released`() = runTest {
        val h = harness()
        repeat(3) { index ->
            val c = h.tap(file = File(temp.root, "clip-$index.wav"))
            h.source.samples(2, LOUD)
            h.source.awaitIdle("read")
            assertEquals(CaptureResult.Saved(c.file), h.stopAndSave(c))
            assertEquals(Ready, store.state.value.status)
        }

        assertEquals(1, h.sources.size)
        assertEquals(1, h.source.calls.count { it == "start" })
        assertEquals(0, h.source.stopCount.get())
        assertEquals(0, h.source.releaseCount.get())
    }

    // Read errors and the restart budget

    @Test
    fun `an idle read error restarts the source on the reader thread`() = runTest {
        val h = harness()
        val first = h.source
        first.enqueue(Step.Code(-32))

        eventually("restart") { h.sources.size == 2 }
        h.source.awaitStarted()
        h.source.awaitIdle("skip")

        with(h) { first.assertReleasedOnceByReader() }
        assertTrue(h.logged("read error -32; restart 1/3"))
        assertTrue(h.lost.isEmpty())
    }

    @Test
    fun `a second read error within 60 s loses capture`() = runTest {
        val h = harness()
        h.source.enqueue(Step.Code(-32))
        eventually("restart") { h.sources.size == 2 }
        h.source.awaitIdle("skip")
        advance(CaptureLoop.MIN_RESTART_INTERVAL_MS - 1)

        h.source.enqueue(Step.Code(-32))

        eventually("lost") {
            runCurrent()
            h.lost.isNotEmpty()
        }
        assertEquals(listOf("restart budget used up"), h.lost)
        assertEquals(2, h.sources.size)
        with(h) { h.source.assertReleasedOnceByReader() }
    }

    @Test
    fun `the fourth restart loses capture even when spaced out`() = runTest {
        val h = harness()
        for (restart in 1..CaptureLoop.MAX_RESTARTS) {
            h.source.enqueue(Step.Code(-32))
            eventually("restart $restart") { h.sources.size == restart + 1 }
            h.source.awaitIdle("skip")
            advance(CaptureLoop.MIN_RESTART_INTERVAL_MS)
        }
        assertTrue(h.lost.isEmpty())

        h.source.enqueue(Step.Code(-32))

        eventually("lost") {
            runCurrent()
            h.lost.isNotEmpty()
        }
        assertEquals(CaptureLoop.MAX_RESTARTS + 1, h.sources.size)
    }

    @Test
    fun `a start failure at session start loses capture and still releases`() = runTest {
        val source = FakeAudioSource().apply { startError = IllegalStateException("fake") }
        val h = harness(first = source, start = false)
        h.start(awaitSkip = false)

        eventually("lost") {
            runCurrent()
            h.lost.isNotEmpty()
        }
        assertEquals(listOf("source start failed"), h.lost)
        assertTrue(h.logged("source start failed: IllegalStateException"))
        with(h) { source.assertReleasedOnceByReader() }
        assertFalse(h.loop.attach(CaptureSession(1, File(temp.root, "x.wav"), store, backgroundScope, { 0L }, {})))
    }

    // Stuck read

    @Test
    fun `a read stuck for more than 2 s loses capture without a restart`() = runTest {
        val h = harness(stuckAfterMs = CaptureLoop.STUCK_READ_MS)
        advance(CaptureLoop.STUCK_READ_MS)
        assertTrue(h.lost.isEmpty())

        advance(CaptureLoop.WATCHDOG_TICK_MS)

        assertEquals(1, h.lost.size)
        assertTrue(h.lost.single().startsWith("read stuck for"))
        assertEquals(1, h.sources.size)
    }

    // Close (teardown)

    @Test
    fun `close cancels an attached clip, stops the source and the reader releases it once`() = runTest {
        val h = harness()
        val c = h.tap()
        h.source.samples(2, LOUD)
        h.source.awaitIdle("read")
        store.dispatch(CaptureEvent.SessionEnded)

        c.session.cancel()
        h.loop.close()

        assertEquals(CaptureResult.Cancelled, c.session.result.getCompleted())
        with(h) { h.source.assertReleasedOnceByReader() }
        assertEquals(1, h.source.stopCount.get())
        eventually("clip discarded") { !c.file.exists() }
        advance(CaptureLoop.CLOSE_BOUND_MS)
        assertFalse(h.logged("possible leak"))
    }

    @Test
    fun `close with a stuck read logs a possible leak and releases when the read returns`() = runTest {
        val h = harness(first = FakeAudioSource(ignoreStop = true))

        h.loop.close()
        advance(CaptureLoop.CLOSE_BOUND_MS)

        assertTrue(h.logged("reader not exited 1000 ms after close; source not released (possible leak)"))
        assertEquals(0, h.source.releaseCount.get())
        h.source.finishBlockedRead()
        with(h) { h.source.assertReleasedOnceByReader() }
    }
}
