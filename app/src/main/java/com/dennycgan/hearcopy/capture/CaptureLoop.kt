package com.dennycgan.hearcopy.capture

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The capture of one session (ADR-005): one [AudioSource] and one reader
 * thread, started once at session start and stopped at teardown. While no
 * clip is attached, every chunk is skipped (read at the native rate and
 * dropped, no conversion, no copy). A tap attaches one [CaptureSession] at a
 * chunk boundary; the clip receives only reads that start after the reader
 * has seen it, and none that start after its stop request.
 *
 * Rules (Cap 1 design 10.4, 10.6):
 * - The reader owns the source: it starts, restarts and releases it. Other
 *   threads only call `stop()`, serialised with `release()` by a lock.
 * - A read error restarts the source within a budget ([MIN_RESTART_INTERVAL_MS],
 *   [MAX_RESTARTS]); an attached clip fails with ReadError first.
 * - Capture is lost when the budget is used up, a read is stuck for more
 *   than [stuckAfterMs], or the source cannot start. [onLost] then runs on
 *   [scope], once; the owner ends the session.
 *
 * [scope] runs the watchdog and [onLost]; [clock] is monotonic milliseconds;
 * [log] is the diagnostics sink.
 */
class CaptureLoop(
    private val sourceFactory: () -> AudioSource,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val log: (String) -> Unit,
    private val onLost: (reason: String) -> Unit,
    private val stuckAfterMs: Long = STUCK_READ_MS,
) {

    private val current = AtomicReference<CaptureSession?>(null)
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val lost = AtomicBoolean(false)
    private val readerExited = CompletableDeferred<Unit>()

    @Volatile private var closing = false
    @Volatile private var lastReturnMs = 0L
    private var watchdog: Job? = null

    private val sourceLock = Any()
    private var source: AudioSource? = null
    private var sourceReleased = false

    // Reader-thread state.
    private var restarts = 0
    private var lastRestartMs = Long.MIN_VALUE
    private var loggedEmptySkip = false
    private var idleReads = 0L
    private var longestGapMs = 0L
    private var lastIdleLogMs = 0L

    /** Starts the source and the reader. Call once, on the main thread. */
    fun start() {
        check(started.compareAndSet(false, true)) { "CaptureLoop started twice" }
        lastReturnMs = clock()
        lastIdleLogMs = lastReturnMs
        watchdog = scope.launch {
            while (!closing) {
                delay(WATCHDOG_TICK_MS)
                val silentFor = clock() - lastReturnMs
                if (!closing && silentFor > stuckAfterMs) {
                    lose("read stuck for $silentFor ms")
                    break
                }
            }
        }
        Thread({ runReader() }, READER_THREAD_NAME).start()
    }

    /**
     * Attaches [session] as the clip; the reader picks it up at its next
     * chunk boundary. Returns false if a clip is still attached or capture
     * is ending. Main thread.
     */
    fun attach(session: CaptureSession): Boolean {
        if (closing || lost.get()) return false
        return current.compareAndSet(null, session)
    }

    /**
     * Ends the capture: stops the source, waits up to [CLOSE_BOUND_MS] for
     * the reader to exit and release it, and logs a possible leak otherwise.
     * An attached clip is cancelled by the reader on its way out. Main thread.
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        closing = true
        watchdog?.cancel()
        stopSource()
        scope.launch {
            val exited = withTimeoutOrNull(CLOSE_BOUND_MS) { readerExited.await() } != null
            if (!exited) log("reader not exited $CLOSE_BOUND_MS ms after close; source not released (possible leak)")
        }
    }

    // Reader side.

    private fun runReader() {
        try {
            if (openSource()) readLoop()
        } catch (e: Exception) {
            log("reader failed: ${e.javaClass.simpleName}")
            lose("reader failed")
        } finally {
            current.get()?.let { clip ->
                clip.cancel()
                detach(clip)
            }
            releaseSource()
            readerExited.complete(Unit)
        }
    }

    private fun readLoop() {
        val buffer = ShortArray(CaptureSession.CHUNK_SAMPLES)
        while (!closing && !lost.get()) {
            val clip = current.get()
            if (clip == null) {
                val n = activeSource().skip()
                stamp(idle = true)
                if (closing) return
                when {
                    n < 0 -> if (!restart(n)) return
                    n == 0 -> {
                        if (!loggedEmptySkip) {
                            loggedEmptySkip = true
                            log("skip returned 0; waiting $EMPTY_READ_SLEEP_MS ms between reads")
                        }
                        Thread.sleep(EMPTY_READ_SLEEP_MS)
                    }
                }
                continue
            }
            try {
                if (!clip.isOpen) clip.open()
                if (!clip.wantsAudio) {
                    detach(clip)
                    continue
                }
                val n = activeSource().read(buffer, 0, clip.readSize())
                stamp(idle = false)
                if (closing) return
                when {
                    n > 0 -> clip.accept(buffer, n)
                    n == 0 -> {
                        clip.emptyRead()
                        Thread.sleep(EMPTY_READ_SLEEP_MS)
                    }
                    else -> {
                        // After a stop request any return value is a normal end of the clip.
                        if (clip.wantsAudio) clip.readFailed(n)
                        detach(clip)
                        if (!restart(n)) return
                    }
                }
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                clip.readerFailed(e)
                detach(clip)
            }
        }
    }

    /** Clears [clip] at a chunk boundary, then lets it finish. */
    private fun detach(clip: CaptureSession) {
        current.compareAndSet(clip, null)
        clip.finishOnReader()
    }

    private fun openSource(): Boolean {
        val next = sourceFactory()
        synchronized(sourceLock) {
            source = next
            sourceReleased = false
        }
        return try {
            next.start()
            lastReturnMs = clock()
            true
        } catch (e: Exception) {
            log("source start failed: ${e.javaClass.simpleName}")
            lose("source start failed")
            false
        }
    }

    /** Restarts the source after a read error, within the budget. */
    private fun restart(code: Int): Boolean {
        val now = clock()
        val tooSoon = lastRestartMs != Long.MIN_VALUE && now - lastRestartMs < MIN_RESTART_INTERVAL_MS
        if (restarts >= MAX_RESTARTS || tooSoon) {
            log("read error $code; restart budget used up")
            lose("restart budget used up")
            return false
        }
        restarts++
        lastRestartMs = now
        log("read error $code; restart $restarts/$MAX_RESTARTS")
        releaseSource()
        if (closing) return false
        return openSource()
    }

    private fun activeSource(): AudioSource = synchronized(sourceLock) { checkNotNull(source) }

    private fun stamp(idle: Boolean) {
        val now = clock()
        val gap = now - lastReturnMs
        lastReturnMs = now
        if (!idle) return
        idleReads++
        if (gap > longestGapMs) longestGapMs = gap
        if (now - lastIdleLogMs >= IDLE_LOG_INTERVAL_MS) {
            log("idle: $idleReads reads, longest gap $longestGapMs ms")
            idleReads = 0
            longestGapMs = 0
            lastIdleLogMs = now
        }
    }

    private fun lose(reason: String) {
        if (!lost.compareAndSet(false, true)) return
        log("capture lost: $reason")
        scope.launch { onLost(reason) }
    }

    /** Skipped once release has started; serialised with [releaseSource]. */
    private fun stopSource() {
        synchronized(sourceLock) {
            val active = source ?: return
            if (sourceReleased) return
            try {
                active.stop()
            } catch (e: Exception) {
                log("source stop failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun releaseSource(): Unit = synchronized(sourceLock) {
        val active = source ?: return
        if (sourceReleased) return
        sourceReleased = true
        try {
            active.release()
        } catch (e: Exception) {
            log("source release failed: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        const val READER_THREAD_NAME = "HearCopyCaptureReader"
        const val STUCK_READ_MS = 2_000L
        const val WATCHDOG_TICK_MS = 1_000L
        const val MIN_RESTART_INTERVAL_MS = 60_000L
        const val MAX_RESTARTS = 3
        const val CLOSE_BOUND_MS = 1_000L
        const val EMPTY_READ_SLEEP_MS = 10L
        const val IDLE_LOG_INTERVAL_MS = 600_000L
    }
}
