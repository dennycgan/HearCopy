package com.dennycgan.hearcopy.capture

import com.dennycgan.hearcopy.capture.FakeAudioSource.Step
import com.dennycgan.hearcopy.capture.LoopHarness.Companion.LOUD
import com.dennycgan.hearcopy.capture.LoopHarness.Companion.awaitBlocking
import com.dennycgan.hearcopy.capture.LoopHarness.Companion.eventually
import com.dennycgan.hearcopy.state.CaptureEvent
import com.dennycgan.hearcopy.state.CaptureStateStore
import com.dennycgan.hearcopy.state.CaptureStatus
import com.dennycgan.hearcopy.state.CaptureStatus.Preparing
import com.dennycgan.hearcopy.state.CaptureStatus.Processing
import com.dennycgan.hearcopy.state.CaptureStatus.Ready
import com.dennycgan.hearcopy.state.CaptureStatus.Recording
import com.dennycgan.hearcopy.state.FailureReason
import com.dennycgan.hearcopy.state.MIN_CLIP_MS
import java.io.File
import java.io.FileNotFoundException
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

/**
 * One attempt's clip, fed by a [CaptureLoop] (see [LoopHarness] for the
 * timing model). Under ADR-005 the source keeps running: a stop never
 * stops it, and a read that started before the stop request still belongs
 * to the clip.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureSessionTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val store = CaptureStateStore()
    private val harnesses = mutableListOf<LoopHarness>()

    private val status: CaptureStatus get() = store.state.value.status

    @After
    fun tearDown() {
        harnesses.forEach { it.shutdown() }
    }

    private fun TestScope.harness(first: FakeAudioSource = FakeAudioSource()): LoopHarness =
        LoopHarness(this, store, temp.root, first).also {
            harnesses += it
            it.start()
        }

    private fun TestScope.advance(ms: Long) {
        testScheduler.advanceTimeBy(ms)
        runCurrent()
    }

    private val chunkBytes = CaptureSession.CHUNK_SAMPLES * 2

    // First audio and read results

    @Test
    fun `first chunk after the tap moves Preparing to Recording at the clock time`() = runTest {
        val h = harness()
        val c = h.tap()
        advance(300)
        assertEquals(Preparing, status)

        h.source.samples(1, LOUD)
        h.source.awaitIdle("read")

        assertEquals(Recording(300), status)
        assertTrue(h.logged("attempt=${c.attempt} first data 300 ms after start"))
    }

    @Test
    fun `reads returning 0 are not audio and are spaced out, logged once`() = runTest {
        val h = harness()
        h.tap()
        h.source.enqueue(*Array(5) { Step.Code(0) })
        h.source.awaitIdle("read")
        assertEquals(Preparing, status)

        val reads = h.source.readTimesNs
        val gapMs = (reads[reads.size - 1] - reads[reads.size - 6]) / 1_000_000
        assertTrue("reads took $gapMs ms", gapMs >= 45)
        assertEquals(1, h.logs.count { it.contains("read returned 0") })

        h.source.samples(1, LOUD)
        h.source.awaitIdle("read")
        assertEquals(Recording(0), status)
    }

    @Test
    fun `negative read during Preparing fails with ReadError before the source restarts`() = runTest {
        val h = harness()
        val c = h.tap()
        val first = h.source
        first.enqueue(Step.Code(-3))

        assertEquals(CaptureResult.Failed(FailureReason.ReadError), c.session.result.awaitBlocking())
        assertEquals(CaptureStatus.Failed(FailureReason.ReadError), status)
        assertTrue(h.logged("attempt=${c.attempt} read failed: code -3"))
        eventually("restart") { h.sources.size == 2 }
        h.source.awaitIdle("skip")
        assertTrue(h.logged("read error -3; restart 1/3"))
        assertFalse(c.file.exists())
        with(h) { first.assertReleasedOnceByReader() }
        assertTrue(h.lost.isEmpty())
    }

    @Test
    fun `negative read during Recording fails with ReadError`() = runTest {
        val h = harness()
        val c = h.tap()
        h.source.samples(2, LOUD)
        h.source.awaitIdle("read")
        h.source.enqueue(Step.Code(-6))

        assertEquals(CaptureResult.Failed(FailureReason.ReadError), c.session.result.awaitBlocking())
        assertEquals(CaptureStatus.Failed(FailureReason.ReadError), status)
        assertFalse(c.file.exists())
        eventually("restart") { h.sources.size == 2 }
    }

    @Test
    fun `write IOException fails with ReadError, names the exception and keeps the source`() = runTest {
        val h = harness()
        val c = h.tap(file = File(temp.root, "missing-dir/capture.wav"), syncOn = "skip")

        assertEquals(CaptureResult.Failed(FailureReason.ReadError), c.session.result.awaitBlocking())
        assertEquals(CaptureStatus.Failed(FailureReason.ReadError), status)
        assertTrue(h.logged("write failed: ${FileNotFoundException::class.java.simpleName}"))
        assertEquals(1, h.sources.size)
        assertEquals(0, h.source.releaseCount.get())
    }

    // Preparing timeout

    @Test
    fun `no audio within 2 s fails with PreparingTimeout without stopping the source`() = runTest {
        val h = harness()
        val c = h.tap()
        advance(1_999)
        assertEquals(Preparing, status)

        advance(1)
        assertEquals(CaptureStatus.Failed(FailureReason.PreparingTimeout), status)
        // The controller claims the outcome at once.
        assertEquals(CaptureResult.Failed(FailureReason.PreparingTimeout), c.session.result.getCompleted())
        assertEquals(0, h.source.stopCount.get())

        // The in-flight read returns; the reader then detaches and discards the clip.
        h.source.samples(1, LOUD)
        h.source.awaitIdle("skip")
        assertFalse(c.file.exists())
        assertEquals(CaptureStatus.Failed(FailureReason.PreparingTimeout), status)
        assertEquals(0, h.source.releaseCount.get())
    }

    @Test
    fun `first audio just before the timeout keeps recording`() = runTest {
        val h = harness()
        h.tap()
        advance(1_999)
        h.source.samples(1, LOUD)
        h.source.awaitIdle("read")
        assertEquals(Recording(1_999), status)

        advance(1)
        assertEquals(Recording(1_999), status)
    }

    // Manual stop

    @Test
    fun `manual stop moves to Processing at once, keeps the in-flight read and saves the clip`() = runTest {
        val h = harness()
        val c = h.tap()
        h.source.samples(5, LOUD)
        h.source.awaitIdle("read")

        advance(MIN_CLIP_MS)
        c.session.stop()
        assertEquals(Processing, status)
        // The read that was in flight at the stop started before it.
        h.source.samples(1, LOUD)

        assertEquals(CaptureResult.Saved(c.file), c.session.result.awaitBlocking())
        // Rewritten in phase 2.4 (owner, Q1): was Ready; a saved clip stays in Processing for its upload.
        assertEquals(Processing, status)
        val bytes = c.file.readBytes()
        assertEquals(44 + 6 * chunkBytes, bytes.size)
        WavWriterTest.assertHeader(bytes, dataBytes = 6 * chunkBytes)
        h.source.awaitIdle("skip")
        assertEquals(0, h.source.stopCount.get())
        assertEquals(0, h.source.releaseCount.get())

        advance(CaptureSession.READER_EXIT_BOUND_MS)
        assertFalse(h.logged("reader not detached"))
    }

    @Test
    fun `a silent clip fails with NoAudio and is deleted`() = runTest {
        val h = harness()
        val c = h.tap()
        h.source.samples(5, 0)
        h.source.awaitIdle("read")

        advance(MIN_CLIP_MS)
        c.session.stop()
        h.source.samples(1, 0)

        assertEquals(CaptureResult.Failed(FailureReason.NoAudio), c.session.result.awaitBlocking())
        assertEquals(CaptureStatus.Failed(FailureReason.NoAudio), status)
        assertTrue(h.logged("no non-silent frame"))
        assertFalse(c.file.exists())
    }

    @Test
    fun `a negative read after a stop request ends the clip normally and restarts the source`() = runTest {
        val h = harness()
        val c = h.tap()
        h.source.samples(3, LOUD)
        h.source.awaitIdle("read")

        advance(MIN_CLIP_MS)
        c.session.stop()
        h.source.enqueue(Step.Code(-3))

        assertEquals(CaptureResult.Saved(c.file), c.session.result.awaitBlocking())
        // Rewritten in phase 2.4 (owner, Q1): was Ready; a saved clip stays in Processing for its upload.
        assertEquals(Processing, status)
        eventually("restart") { h.logged("read error -3; restart 1/3") }
    }

    @Test
    fun `stop during Preparing is a no-op and the clip still records`() = runTest {
        val h = harness()
        val c = h.tap()

        c.session.stop()
        assertEquals(Preparing, status)

        h.source.samples(1, LOUD)
        h.source.awaitIdle("read")
        assertEquals(Recording(0), status)

        advance(MIN_CLIP_MS)
        c.session.stop()
        h.source.samples(1, LOUD)
        assertEquals(CaptureResult.Saved(c.file), c.session.result.awaitBlocking())
    }

    // Minimum clip length

    @Test
    fun `a stop before the minimum clip length returns to Ready at once and deletes the clip`() = runTest {
        val h = harness()
        val c = h.tap()
        h.source.samples(3, LOUD)
        h.source.awaitIdle("read")
        advance(MIN_CLIP_MS - 1)

        c.session.stop()
        assertEquals(Ready, status)
        assertEquals(c.attempt, store.state.value.attempt)
        assertEquals(CaptureResult.TooShort, c.session.result.getCompleted())
        assertTrue(h.logged("attempt=${c.attempt} discarded: too short (999 ms)"))

        // The read in flight at the stop ends the clip; the reader deletes it.
        h.source.samples(1, LOUD)
        h.source.awaitIdle("skip")
        assertFalse(c.file.exists())
        assertEquals(Ready, status)
        assertEquals(0, h.source.stopCount.get())

        advance(CaptureSession.READER_EXIT_BOUND_MS)
        assertFalse(h.logged("reader not detached"))
        assertFalse(h.logged("no non-silent frame"))
        assertEquals(Ready, status)
    }

    @Test
    fun `the minimum is measured from first audio, not from the tap`() = runTest {
        val h = harness()
        val c = h.tap()
        advance(1_500)
        h.source.samples(1, LOUD)
        h.source.awaitIdle("read")
        assertEquals(Recording(1_500), status)
        advance(MIN_CLIP_MS - 1)

        c.session.stop()
        assertEquals(Ready, status)
        assertEquals(CaptureResult.TooShort, c.session.result.getCompleted())
        h.source.samples(1, LOUD)
        h.source.awaitIdle("skip")
        assertFalse(c.file.exists())
    }

    @Test
    fun `a stuck read after a too-short stop keeps Ready and deletes the clip when it returns`() = runTest {
        val h = harness()
        val c = h.tap()
        h.source.samples(1, LOUD)
        h.source.awaitIdle("read")
        advance(500)

        c.session.stop()
        advance(CaptureSession.READER_EXIT_BOUND_MS)
        assertTrue(h.logged("reader not detached"))
        assertEquals(Ready, status)
        assertEquals(CaptureResult.TooShort, c.session.result.getCompleted())

        h.source.samples(1, LOUD)
        h.source.awaitIdle("skip")
        assertFalse(c.file.exists())
        assertEquals(Ready, status)
    }

    @Test
    fun `the next tap after a discarded clip records a new attempt`() = runTest {
        val h = harness()
        val first = h.tap(file = File(temp.root, "first.wav"))
        h.source.samples(1, LOUD)
        h.source.awaitIdle("read")
        first.session.stop()
        h.source.samples(1, LOUD)
        h.source.awaitIdle("skip")
        assertEquals(Ready, status)

        val second = h.tap(file = File(temp.root, "second.wav"))
        assertEquals(first.attempt + 1, second.attempt)
        h.source.samples(1, LOUD)
        h.source.awaitIdle("read")
        advance(MIN_CLIP_MS)
        second.session.stop()
        h.source.samples(1, LOUD)

        assertEquals(CaptureResult.Saved(second.file), second.session.result.awaitBlocking())
        assertFalse(first.file.exists())
    }

    // Stuck read (bounded wait)

    @Test
    fun `manual stop with a stuck read fails with ReadError after 1 s and discards the clip when it returns`() =
        runTest {
            val h = harness()
            val c = h.tap()
            h.source.samples(3, LOUD)
            h.source.awaitIdle("read")

            advance(MIN_CLIP_MS)
            c.session.stop()
            assertEquals(Processing, status)

            advance(CaptureSession.READER_EXIT_BOUND_MS - 1)
            assertEquals(Processing, status)
            assertFalse(c.session.result.isCompleted)

            advance(1)
            assertEquals(CaptureStatus.Failed(FailureReason.ReadError), status)
            assertEquals(CaptureResult.Failed(FailureReason.ReadError), c.session.result.getCompleted())
            assertTrue(h.logged("reader not detached 1000 ms after stop; clip abandoned"))

            h.source.samples(1, LOUD)
            h.source.awaitIdle("skip")
            assertFalse(c.file.exists())
            eventually("late detach log") { h.logged("reader detached late; clip discarded") }
            assertEquals(CaptureStatus.Failed(FailureReason.ReadError), status)
        }

    @Test
    fun `Preparing timeout with a stuck read keeps PreparingTimeout and discards the clip when it returns`() =
        runTest {
            val h = harness()
            val c = h.tap()

            advance(CaptureSession.PREPARING_TIMEOUT_MS)
            assertEquals(CaptureStatus.Failed(FailureReason.PreparingTimeout), status)

            advance(CaptureSession.READER_EXIT_BOUND_MS)
            assertTrue(h.logged("reader not detached"))
            assertEquals(CaptureStatus.Failed(FailureReason.PreparingTimeout), status)

            h.source.samples(1, LOUD)
            h.source.awaitIdle("skip")
            assertFalse(c.file.exists())
        }

    // 300 s dual cap

    @Test
    fun `sample cap stops at exactly 4,800,000 samples`() = runTest {
        val h = harness()
        val c = h.tap()
        h.source.endless = Step.Samples(1_000, LOUD)

        assertEquals(CaptureResult.Saved(c.file), c.session.result.awaitBlocking())
        h.source.endless = null
        // Rewritten in phase 2.4 (owner, Q1): was Ready; a saved clip stays in Processing for its upload.
        assertEquals(Processing, status)
        assertTrue(h.logged("limit reached by sample count"))
        val dataBytes = CaptureSession.MAX_SAMPLES * 2
        assertEquals(4_800_000, CaptureSession.MAX_SAMPLES)
        assertEquals(44L + dataBytes, c.file.length())
        WavWriterTest.assertHeader(c.file.inputStream().use { it.readNBytes(44) }, dataBytes)
    }

    @Test
    fun `clock cap fires 300 s after first audio`() = runTest {
        val h = harness()
        val c = h.tap()
        advance(1_500)
        h.source.samples(1, LOUD)
        h.source.awaitIdle("read")
        assertEquals(Recording(1_500), status)

        advance(CaptureSession.MAX_DURATION_MS - 1)
        assertEquals(Recording(1_500), status)

        advance(1)
        assertEquals(Processing, status)
        h.source.samples(1, LOUD)
        assertEquals(CaptureResult.Saved(c.file), c.session.result.awaitBlocking())
        // Rewritten in phase 2.4 (owner, Q1): was Ready; a saved clip stays in Processing for its upload.
        assertEquals(Processing, status)
        assertTrue(h.logged("limit reached by clock"))
    }

    // Cancel

    @Test
    fun `cancel sends no event, never finalises, and the clip is discarded`() = runTest {
        val h = harness()
        val c = h.tap()
        h.source.samples(3, LOUD)
        h.source.awaitIdle("read")

        c.session.cancel()

        assertEquals(CaptureResult.Cancelled, c.session.result.getCompleted())
        h.source.samples(1, LOUD)
        h.source.awaitIdle("skip")
        assertFalse(c.file.exists())
        assertEquals(Recording(0), status)
    }

    // Diagnostics

    @Test
    fun `diagnostics report first non-silent offset and loudest frame level`() = runTest {
        val h = harness()
        val c = h.tap()
        h.source.samples(3, 0)
        h.source.samples(1, 1_000)
        h.source.awaitIdle("read")

        advance(MIN_CLIP_MS)
        c.session.stop()
        h.source.samples(1, 0)

        assertEquals(CaptureResult.Saved(c.file), c.session.result.awaitBlocking())
        assertTrue(h.logged("attempt=${c.attempt} first non-silent frame 300 ms after first data"))
        assertTrue(h.logged("attempt=${c.attempt} loudest frame -30.3 dBFS"))
    }

    // Hardening (H1, H2)

    @Test
    fun `H1 a stale session's stop leaves the newer attempt in Recording`() = runTest {
        val oldLoop = harness()
        val old = oldLoop.tap(file = File(temp.root, "old.wav"))
        oldLoop.source.samples(1, LOUD)
        oldLoop.source.awaitIdle("read")
        store.dispatch(CaptureEvent.SessionEnded)

        val currentLoop = harness()
        val current = currentLoop.tap(file = File(temp.root, "current.wav"))
        currentLoop.source.samples(1, LOUD)
        currentLoop.source.awaitIdle("read")
        assertEquals(current.attempt, store.state.value.attempt)
        assertTrue(status is Recording)

        old.session.stop()

        assertTrue(status is Recording)
        assertEquals(current.attempt, store.state.value.attempt)
        assertFalse(old.session.result.isCompleted)
    }

    @Test
    fun `H2 an unexpected exception while finalising fails with ReadError and deletes the file`() = runTest {
        val h = harness()
        val file = ThrowOnceFile(temp.root, "capture.wav")
        val c = h.tap(file = file)
        h.source.samples(3, LOUD)
        h.source.awaitIdle("read")
        file.armed = true

        advance(MIN_CLIP_MS)
        c.session.stop()
        h.source.samples(1, LOUD)

        assertEquals(CaptureResult.Failed(FailureReason.ReadError), c.session.result.awaitBlocking())
        assertEquals(CaptureStatus.Failed(FailureReason.ReadError), status)
        assertFalse(File(file.parentFile, file.name).exists())
    }

    // Stop time (phase 2.4): only attempts that reached Processing get a notification

    @Test
    fun `a manual stop records its time, also for a clip that turns out silent`() = runTest {
        val h = harness()
        val c = h.tap()
        h.source.samples(5, 0)
        h.source.awaitIdle("read")

        advance(MIN_CLIP_MS)
        val stopAt = testScheduler.currentTime
        c.session.stop()
        h.source.samples(1, 0)

        assertEquals(CaptureResult.Failed(FailureReason.NoAudio), c.session.result.awaitBlocking())
        assertEquals(stopAt, c.session.stoppedAtMs)
    }

    @Test
    fun `the clock limit records its time`() = runTest {
        val h = harness()
        val c = h.tap()
        advance(1_500)
        h.source.samples(1, LOUD)
        h.source.awaitIdle("read")

        advance(CaptureSession.MAX_DURATION_MS)
        assertEquals(Processing, status)
        assertEquals(1_500 + CaptureSession.MAX_DURATION_MS, c.session.stoppedAtMs)
        h.source.samples(1, LOUD)
        assertEquals(CaptureResult.Saved(c.file), c.session.result.awaitBlocking())
    }

    @Test
    fun `the sample cap records its time`() = runTest {
        val h = harness()
        val c = h.tap()
        h.source.endless = Step.Samples(1_000, LOUD)

        assertEquals(CaptureResult.Saved(c.file), c.session.result.awaitBlocking())
        h.source.endless = null
        assertEquals(testScheduler.currentTime, c.session.stoppedAtMs)
    }

    @Test
    fun `attempts that never reached Processing have no stop time`() = runTest {
        val h = harness()
        val timedOut = h.tap()
        advance(CaptureSession.PREPARING_TIMEOUT_MS)
        h.source.samples(1, LOUD)
        h.source.awaitIdle("skip")
        assertEquals(CaptureResult.Failed(FailureReason.PreparingTimeout), timedOut.session.result.awaitBlocking())
        assertEquals(null, timedOut.session.stoppedAtMs)

        val readError = h.tap()
        h.source.samples(2, LOUD)
        h.source.awaitIdle("read")
        h.source.enqueue(Step.Code(-6))
        assertEquals(CaptureResult.Failed(FailureReason.ReadError), readError.session.result.awaitBlocking())
        assertEquals(null, readError.session.stoppedAtMs)
    }

    @Test
    fun `a clip discarded as too short has no stop time`() = runTest {
        val h = harness()
        val c = h.tap()
        h.source.samples(2, LOUD)
        h.source.awaitIdle("read")

        advance(MIN_CLIP_MS - 1)
        c.session.stop()
        h.source.samples(1, LOUD)

        assertEquals(CaptureResult.TooShort, c.session.result.awaitBlocking())
        assertEquals(null, c.session.stoppedAtMs)
    }

    /** `getPath()` throws exactly once after [armed] is set. */
    private class ThrowOnceFile(parent: File, name: String) : File(parent, name) {
        @Volatile var armed = false

        override fun getPath(): String {
            if (armed) {
                armed = false
                throw IllegalStateException("injected")
            }
            return super.getPath()
        }
    }
}
