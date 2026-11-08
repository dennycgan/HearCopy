package com.dennycgan.hearcopy.capture

import com.dennycgan.hearcopy.capture.FakeAudioSource.Companion.GUARD_SECONDS
import com.dennycgan.hearcopy.capture.FakeAudioSource.Step
import com.dennycgan.hearcopy.state.CaptureEvent
import com.dennycgan.hearcopy.state.CaptureStateStore
import com.dennycgan.hearcopy.state.CaptureStatus
import com.dennycgan.hearcopy.state.CaptureStatus.Preparing
import com.dennycgan.hearcopy.state.CaptureStatus.Processing
import com.dennycgan.hearcopy.state.CaptureStatus.Ready
import com.dennycgan.hearcopy.state.CaptureStatus.Recording
import com.dennycgan.hearcopy.state.FailureReason
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Timers run on the test scheduler's virtual time; the reader is a real
 * thread synchronised through [FakeAudioSource] latches. The test body never
 * suspends while the reader runs, so virtual time moves only when a test
 * calls [advance].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureSessionTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val store = CaptureStateStore()
    private val logs = CopyOnWriteArrayList<String>()
    private val sessions = mutableListOf<CaptureSession>()
    private val sources = mutableListOf<FakeAudioSource>()

    private val status: CaptureStatus get() = store.state.value.status

    private class Fixture(val attempt: Int, val session: CaptureSession, val source: FakeAudioSource, val file: File)

    @After
    fun tearDown() {
        sessions.forEach { it.cancel() }
        sources.forEach { it.shutdown() }
        sources.forEach { it.awaitReleased() }
    }

    private fun TestScope.begin(
        source: FakeAudioSource = FakeAudioSource(),
        file: File = File(temp.root, "capture.wav"),
    ): Fixture {
        store.dispatch(CaptureEvent.SessionStarted)
        val attempt = store.dispatch(CaptureEvent.TapStart).attempt
        val session = CaptureSession(
            attempt = attempt,
            source = source,
            outputFile = file,
            store = store,
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
            log = logs::add,
        )
        sessions += session
        sources += source
        session.start()
        runCurrent()
        return Fixture(attempt, session, source, file)
    }

    private fun TestScope.advance(ms: Long) {
        testScheduler.advanceTimeBy(ms)
        runCurrent()
    }

    private fun <T> Deferred<T>.awaitBlocking(): T {
        val done = CountDownLatch(1)
        invokeOnCompletion { done.countDown() }
        if (!done.await(GUARD_SECONDS, TimeUnit.SECONDS)) fail("result did not complete")
        return getCompleted()
    }

    private fun eventually(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(GUARD_SECONDS)
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for $what")
            Thread.sleep(1)
        }
    }

    /** B4: release once, by the reader, after its last read returned. */
    private fun FakeAudioSource.assertReleasedByReaderAfterLoop() {
        assertEquals(1, releaseCount.get())
        assertEquals(listOf(CaptureSession.READER_THREAD_NAME), releaseThreads)
        assertEquals("release", calls.last())
        assertEquals(calls.count { it == "read" }, calls.count { it == "returned" })
    }

    private fun Fixture.statusAtStop(): () -> CaptureStatus? {
        var seen: CaptureStatus? = null
        source.onStop = { seen = status }
        return { seen }
    }

    private fun logged(text: String) = logs.any { it.contains(text) }

    // First audio and read results

    @Test
    fun `first read with samples moves Preparing to Recording at the clock time`() = runTest {
        val f = begin()
        advance(300)
        assertEquals(Preparing, status)

        f.source.samples(1, LOUD)
        f.source.awaitIdle()

        assertEquals(Recording(300), status)
        assertTrue(logged("attempt=${f.attempt} first data 300 ms after start"))
    }

    @Test
    fun `reads returning 0 are not audio and are spaced out, logged once`() = runTest {
        val f = begin()
        f.source.enqueue(*Array(5) { Step.Code(0) })
        f.source.awaitIdle()
        assertEquals(Preparing, status)

        // Five empty reads, each followed by a sleep before the next read.
        val gapMs = (f.source.readTimesNs[5] - f.source.readTimesNs[0]) / 1_000_000
        assertTrue("reads 0..5 took $gapMs ms", gapMs >= 45)
        assertEquals(1, logs.count { it.contains("read returned 0") })

        f.source.samples(1, LOUD)
        f.source.awaitIdle()
        assertEquals(Recording(0), status)
    }

    @Test
    fun `negative read during Preparing fails with ReadError`() = runTest {
        val f = begin()
        f.source.enqueue(Step.Code(-3))

        assertEquals(CaptureResult.Failed(FailureReason.ReadError), f.session.result.awaitBlocking())
        assertEquals(CaptureStatus.Failed(FailureReason.ReadError), status)
        assertTrue(logged("read failed: code -3"))
        assertFalse(f.file.exists())
        f.source.assertReleasedByReaderAfterLoop()
    }

    @Test
    fun `negative read during Recording fails with ReadError`() = runTest {
        val f = begin()
        f.source.samples(2, LOUD)
        f.source.awaitIdle()
        f.source.enqueue(Step.Code(-6))

        assertEquals(CaptureResult.Failed(FailureReason.ReadError), f.session.result.awaitBlocking())
        assertEquals(CaptureStatus.Failed(FailureReason.ReadError), status)
        assertFalse(f.file.exists())
        f.source.assertReleasedByReaderAfterLoop()
    }

    @Test
    fun `source start failure fails with InitFailed and still releases`() = runTest {
        val source = FakeAudioSource().apply { startError = IllegalStateException("fake") }
        val f = begin(source)

        assertEquals(CaptureResult.Failed(FailureReason.InitFailed), f.session.result.awaitBlocking())
        assertEquals(CaptureStatus.Failed(FailureReason.InitFailed), status)
        assertTrue(logged("source start failed: IllegalStateException"))
        assertFalse(f.file.exists())
        f.source.assertReleasedByReaderAfterLoop()
    }

    @Test
    fun `write IOException fails with ReadError and names the exception`() = runTest {
        val f = begin(file = File(temp.root, "missing-dir/capture.wav"))

        assertEquals(CaptureResult.Failed(FailureReason.ReadError), f.session.result.awaitBlocking())
        assertEquals(CaptureStatus.Failed(FailureReason.ReadError), status)
        assertTrue(logged("write failed: ${FileNotFoundException::class.java.simpleName}"))
        f.source.assertReleasedByReaderAfterLoop()
    }

    // Preparing timeout (B4 case 2, N1)

    @Test
    fun `no audio within 2 s fails with PreparingTimeout and the reader releases after exit`() = runTest {
        val f = begin()
        advance(1_999)
        assertEquals(Preparing, status)
        assertEquals(0, f.source.stopCount.get())

        advance(1)
        assertEquals(CaptureStatus.Failed(FailureReason.PreparingTimeout), status)
        // The controller claims the outcome at once.
        assertTrue(f.session.result.isCompleted)
        assertEquals(CaptureResult.Failed(FailureReason.PreparingTimeout), f.session.result.getCompleted())
        assertEquals(1, f.source.stopCount.get())

        f.source.awaitReleased()
        f.source.assertReleasedByReaderAfterLoop()
        assertFalse(f.file.exists())
        assertEquals(CaptureStatus.Failed(FailureReason.PreparingTimeout), status)
    }

    @Test
    fun `first audio just before the timeout keeps recording`() = runTest {
        val f = begin()
        advance(1_999)
        f.source.samples(1, LOUD)
        f.source.awaitIdle()
        assertEquals(Recording(1_999), status)

        advance(1)
        assertEquals(Recording(1_999), status)
        assertEquals(0, f.source.stopCount.get())
    }

    // Manual stop (B4 case 1)

    @Test
    fun `manual stop moves to Processing at once, then saves a valid clip`() = runTest {
        val f = begin()
        val atStop = f.statusAtStop()
        f.source.samples(5, LOUD)
        f.source.awaitIdle()

        f.session.stop()

        assertEquals(CaptureResult.Saved(f.file), f.session.result.awaitBlocking())
        assertEquals(Processing, atStop())
        assertEquals(Ready, status)
        f.source.awaitReleased()
        f.source.assertReleasedByReaderAfterLoop()
        val bytes = f.file.readBytes()
        assertEquals(44 + 5 * CaptureSession.CHUNK_SAMPLES * 2, bytes.size)
        WavWriterTest.assertHeader(bytes, dataBytes = 5 * CaptureSession.CHUNK_SAMPLES * 2)

        advance(CaptureSession.READER_EXIT_BOUND_MS)
        assertFalse(logged("reader not exited"))
    }

    @Test
    fun `a silent clip fails with NoAudio and is deleted`() = runTest {
        val f = begin()
        f.source.samples(5, 0)
        f.source.awaitIdle()

        f.session.stop()

        assertEquals(CaptureResult.Failed(FailureReason.NoAudio), f.session.result.awaitBlocking())
        assertEquals(CaptureStatus.Failed(FailureReason.NoAudio), status)
        assertTrue(logged("no non-silent frame"))
        f.source.awaitReleased()
        assertFalse(f.file.exists())
    }

    @Test
    fun `a negative read after a stop request is a normal exit`() = runTest {
        val f = begin(FakeAudioSource(returnAfterStop = -3))
        f.source.samples(3, LOUD)
        f.source.awaitIdle()

        f.session.stop()

        assertEquals(CaptureResult.Saved(f.file), f.session.result.awaitBlocking())
        assertEquals(Ready, status)
    }

    @Test
    fun `stop during Preparing is a no-op and the session still records`() = runTest {
        val f = begin()

        f.session.stop()
        assertEquals(Preparing, status)
        assertEquals(0, f.source.stopCount.get())

        f.source.samples(1, LOUD)
        f.source.awaitIdle()
        assertEquals(Recording(0), status)

        f.session.stop()
        assertEquals(CaptureResult.Saved(f.file), f.session.result.awaitBlocking())
    }

    // Reader that ignores stop (B4 case 3)

    @Test
    fun `manual stop with a stuck read fails with ReadError after 1 s and releases only when read returns`() =
        runTest {
            val f = begin(FakeAudioSource(ignoreStop = true))
            f.source.samples(3, LOUD)
            f.source.awaitIdle()

            f.session.stop()
            assertEquals(Processing, status)

            advance(CaptureSession.READER_EXIT_BOUND_MS - 1)
            assertEquals(Processing, status)
            assertFalse(f.session.result.isCompleted)

            advance(1)
            assertEquals(CaptureStatus.Failed(FailureReason.ReadError), status)
            assertEquals(CaptureResult.Failed(FailureReason.ReadError), f.session.result.getCompleted())
            assertTrue(logged("reader not exited 1000 ms after stop; source not released (possible leak)"))
            assertEquals(0, f.source.releaseCount.get())

            f.source.finishBlockedRead()
            f.source.awaitReleased()
            f.source.assertReleasedByReaderAfterLoop()
            assertFalse(f.file.exists())
            eventually("late exit log") { logged("reader exited late; source released") }
            assertEquals(CaptureStatus.Failed(FailureReason.ReadError), status)
        }

    @Test
    fun `Preparing timeout with a stuck read keeps PreparingTimeout and releases only when read returns`() =
        runTest {
            val f = begin(FakeAudioSource(ignoreStop = true))

            advance(CaptureSession.PREPARING_TIMEOUT_MS)
            assertEquals(CaptureStatus.Failed(FailureReason.PreparingTimeout), status)
            assertEquals(CaptureResult.Failed(FailureReason.PreparingTimeout), f.session.result.getCompleted())

            advance(CaptureSession.READER_EXIT_BOUND_MS)
            assertTrue(logged("reader not exited"))
            assertEquals(CaptureStatus.Failed(FailureReason.PreparingTimeout), status)
            assertEquals(0, f.source.releaseCount.get())

            f.source.finishBlockedRead()
            f.source.awaitReleased()
            f.source.assertReleasedByReaderAfterLoop()
            assertFalse(f.file.exists())
        }

    // 300 s dual cap

    @Test
    fun `sample cap stops at exactly 4,800,000 samples`() = runTest {
        val source = FakeAudioSource().apply { endless = Step.Samples(1_000, LOUD) }
        val f = begin(source)

        assertEquals(CaptureResult.Saved(f.file), f.session.result.awaitBlocking())
        assertEquals(Ready, status)
        assertTrue(logged("limit reached by sample count"))
        assertEquals(0, f.source.stopCount.get())
        val dataBytes = CaptureSession.MAX_SAMPLES * 2
        assertEquals(4_800_000, CaptureSession.MAX_SAMPLES)
        assertEquals(44L + dataBytes, f.file.length())
        WavWriterTest.assertHeader(f.file.inputStream().use { it.readNBytes(44) }, dataBytes)
        f.source.awaitReleased()
        f.source.assertReleasedByReaderAfterLoop()
    }

    @Test
    fun `clock cap fires 300 s after first audio even while read blocks`() = runTest {
        val f = begin()
        val atStop = f.statusAtStop()
        advance(1_500)
        f.source.samples(1, LOUD)
        f.source.awaitIdle()
        assertEquals(Recording(1_500), status)

        advance(CaptureSession.MAX_DURATION_MS - 1)
        assertEquals(Recording(1_500), status)
        assertEquals(0, f.source.stopCount.get())

        advance(1)
        assertEquals(CaptureResult.Saved(f.file), f.session.result.awaitBlocking())
        assertEquals(Processing, atStop())
        assertEquals(Ready, status)
        assertTrue(logged("limit reached by clock"))
        f.source.awaitReleased()
        f.source.assertReleasedByReaderAfterLoop()
    }

    // Cancel

    @Test
    fun `cancel sends no event, never finalises, and the reader releases`() = runTest {
        val f = begin()
        f.source.samples(3, LOUD)
        f.source.awaitIdle()

        f.session.cancel()

        assertEquals(CaptureResult.Cancelled, f.session.result.getCompleted())
        f.source.awaitReleased()
        f.source.assertReleasedByReaderAfterLoop()
        assertFalse(f.file.exists())
        assertEquals(Recording(0), status)
    }

    // Diagnostics

    @Test
    fun `diagnostics report first non-silent offset and loudest frame level`() = runTest {
        val f = begin()
        f.source.samples(3, 0)
        f.source.samples(1, 1_000)
        f.source.awaitIdle()

        f.session.stop()

        assertEquals(CaptureResult.Saved(f.file), f.session.result.awaitBlocking())
        assertTrue(logged("attempt=${f.attempt} first non-silent frame 300 ms after first data"))
        assertTrue(logged("attempt=${f.attempt} loudest frame -30.3 dBFS"))
    }

    // Hardening (H1, H2)

    @Test
    fun `H1 a stale session's stop leaves the newer attempt in Recording`() = runTest {
        val old = begin(file = File(temp.root, "old.wav"))
        old.source.samples(1, LOUD)
        old.source.awaitIdle()
        store.dispatch(CaptureEvent.SessionEnded)

        val current = begin(file = File(temp.root, "current.wav"))
        current.source.samples(1, LOUD)
        current.source.awaitIdle()
        assertEquals(current.attempt, store.state.value.attempt)
        assertTrue(status is Recording)

        old.session.stop()

        assertTrue(status is Recording)
        assertEquals(current.attempt, store.state.value.attempt)
        assertEquals(0, old.source.stopCount.get())
    }

    @Test
    fun `H2 an unexpected exception while finalising fails with ReadError and deletes the file`() = runTest {
        val file = ThrowOnceFile(temp.root, "capture.wav")
        val f = begin(file = file)
        f.source.samples(3, LOUD)
        f.source.awaitIdle()
        file.armed = true

        f.session.stop()

        assertEquals(CaptureResult.Failed(FailureReason.ReadError), f.session.result.awaitBlocking())
        assertEquals(CaptureStatus.Failed(FailureReason.ReadError), status)
        assertFalse(File(file.parentFile, file.name).exists())
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

    private companion object {
        const val LOUD: Short = 1_000
    }
}
