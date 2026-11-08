package com.dennycgan.hearcopy.capture

import com.dennycgan.hearcopy.capture.FakeAudioSource.Companion.GUARD_SECONDS
import com.dennycgan.hearcopy.state.CaptureEvent
import com.dennycgan.hearcopy.state.CaptureStateStore
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.fail

/**
 * One [CaptureLoop] with scripted sources. Timers run on [test]'s virtual
 * time; the reader is a real thread synchronised through [FakeAudioSource]
 * latches. Tests never suspend while the reader runs, so virtual time moves
 * only when a test advances it. The stuck-read watchdog is off unless
 * [stuckAfterMs] is given.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoopHarness(
    private val test: TestScope,
    val store: CaptureStateStore,
    private val dir: File,
    private val first: FakeAudioSource = FakeAudioSource(),
    stuckAfterMs: Long = NO_WATCHDOG,
    val logs: MutableList<String> = CopyOnWriteArrayList(),
) {

    val sources = CopyOnWriteArrayList<FakeAudioSource>()
    val lost = CopyOnWriteArrayList<String>()
    private val sessions = CopyOnWriteArrayList<CaptureSession>()

    /** Builds every source after the first one (restarts). */
    var restartSource: () -> FakeAudioSource = { FakeAudioSource() }

    private val clock = { test.testScheduler.currentTime }

    val loop = CaptureLoop(
        sourceFactory = { (if (sources.isEmpty()) first else restartSource()).also { sources += it } },
        scope = test.backgroundScope,
        clock = clock,
        log = logs::add,
        onLost = { lost += it },
        stuckAfterMs = stuckAfterMs,
    )

    /** The newest source. */
    val source: FakeAudioSource get() = sources.last()

    class Clip(val attempt: Int, val session: CaptureSession, val file: File)

    /** Starts the loop; unless [awaitSkip] is false, waits until the reader skips on an empty queue. */
    fun start(awaitSkip: Boolean = true) {
        loop.start()
        first.awaitStarted()
        if (awaitSkip) first.awaitIdle("skip")
    }

    /**
     * A tap: TapStart, a session, attach. The reader is blocked in a skip, so
     * one idle chunk is queued to end it (that chunk is discarded); then waits
     * until the reader blocks in [syncOn] ("read" once it reads for the clip).
     */
    fun tap(file: File = File(dir, "capture.wav"), syncOn: String = "read"): Clip {
        source.awaitIdle("skip")
        if (!store.state.value.setup.projectionActive) store.dispatch(CaptureEvent.SessionStarted)
        val attempt = store.dispatch(CaptureEvent.TapStart).attempt
        val session = CaptureSession(attempt, file, store, test.backgroundScope, clock, logs::add)
        sessions += session
        session.start()
        check(loop.attach(session)) { "attach refused" }
        source.samples(1, IDLE)
        source.awaitIdle(syncOn)
        test.runCurrent()
        return Clip(attempt, session, file)
    }

    fun logged(text: String) = logs.any { it.contains(text) }

    /** Waits until the reader thread that released [source] has ended. */
    fun awaitReaderExited(source: FakeAudioSource = this.source) {
        source.awaitReleased()
        val reader = checkNotNull(source.releasedBy)
        reader.join(TimeUnit.SECONDS.toMillis(GUARD_SECONDS))
        if (reader.isAlive) fail("reader thread did not exit")
    }

    /** Test cleanup: cancels clips, closes the loop, unblocks and awaits every source. */
    fun shutdown() {
        sessions.forEach { it.cancel() }
        loop.close()
        sources.forEach { it.shutdown() }
        sources.forEach { it.awaitReleased() }
    }

    /** Released once, by the reader thread, as the source's last call. */
    fun FakeAudioSource.assertReleasedOnceByReader() {
        awaitReleased()
        assertEquals(1, releaseCount.get())
        assertEquals(listOf(CaptureLoop.READER_THREAD_NAME), releaseThreads)
        assertEquals("release", calls.last())
    }

    companion object {
        const val NO_WATCHDOG = Long.MAX_VALUE / 4
        const val IDLE: Short = 7
        const val LOUD: Short = 1_000

        fun <T> Deferred<T>.awaitBlocking(): T {
            val done = CountDownLatch(1)
            invokeOnCompletion { done.countDown() }
            if (!done.await(GUARD_SECONDS, TimeUnit.SECONDS)) fail("result did not complete")
            @OptIn(ExperimentalCoroutinesApi::class)
            return getCompleted()
        }

        fun eventually(what: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(GUARD_SECONDS)
            while (!condition()) {
                if (System.nanoTime() > deadline) fail("timed out waiting for $what")
                Thread.sleep(1)
            }
        }
    }
}
