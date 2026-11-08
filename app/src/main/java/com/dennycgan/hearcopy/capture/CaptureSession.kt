package com.dennycgan.hearcopy.capture

import com.dennycgan.hearcopy.state.CaptureEvent
import com.dennycgan.hearcopy.state.CaptureState
import com.dennycgan.hearcopy.state.CaptureStateStore
import com.dennycgan.hearcopy.state.CaptureStatus
import com.dennycgan.hearcopy.state.FailureReason
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** How one capture attempt ended. */
sealed interface CaptureResult {
    /** A valid, finalised clip. The caller now owns [file]. */
    data class Saved(val file: File) : CaptureResult

    /** The clip was discarded; its file is (or will be) deleted. */
    data class Failed(val reason: FailureReason) : CaptureResult

    /** Ended by [CaptureSession.cancel]; no state event was sent. */
    data object Cancelled : CaptureResult
}

/**
 * One capture attempt: reads [source] on a dedicated reader thread, streams
 * to [outputFile] and drives the attempt's state events.
 *
 * Rules (Cap 1 design, B4):
 * - The reader owns `release()`: it calls it once, after its read loop has
 *   exited. The controller (timers, [stop], [cancel]) only calls `stop()`.
 * - WAV finalisation and the silence verdict run on the reader thread after
 *   the loop has exited, never concurrently with it.
 * - One outcome per attempt: the first to claim the outcome flag decides.
 *   Preparing timeout and [cancel] claim it at once. Manual stop and the
 *   300 s limit leave it to the reader for [READER_EXIT_BOUND_MS]; if the
 *   reader has not exited by then, the clip is Failed(ReadError).
 * - Racing events are arbitrated by the state returned from `dispatch`.
 *
 * [scope] runs only the timers; [clock] is monotonic milliseconds; [log] is
 * the diagnostics sink.
 */
class CaptureSession(
    private val attempt: Int,
    private val source: AudioSource,
    private val outputFile: File,
    private val store: CaptureStateStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val log: (String) -> Unit,
) {

    private val mutableResult = CompletableDeferred<CaptureResult>()

    /** Completes once with this attempt's outcome. */
    val result: Deferred<CaptureResult> = mutableResult

    private val started = AtomicBoolean(false)
    private val outcomeClaimed = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val stopPathEntered = AtomicBoolean(false)
    private val loopExited = CompletableDeferred<Unit>()

    private val sourceLock = Any()
    private var released = false

    @Volatile private var startedAtMs = 0L
    @Volatile private var preparingTimer: Job? = null
    @Volatile private var limitTimer: Job? = null
    @Volatile private var reportedStuck = false

    // Reader-thread state.
    private var writer: WavWriter? = null
    private val detector = SilenceDetector()

    /**
     * Starts the attempt. Call once, on the main thread, right after
     * `TapStart` returned Preparing for [attempt].
     */
    fun start() {
        check(started.compareAndSet(false, true)) { "CaptureSession started twice" }
        startedAtMs = clock()
        val deadline = startedAtMs + PREPARING_TIMEOUT_MS
        preparingTimer = scope.launch {
            delay(deadline - clock())
            onPreparingTimeout()
        }
        Thread({ runReader() }, READER_THREAD_NAME).start()
    }

    /**
     * Manual stop. Dispatches `TapStop`; continues only if that leaves this
     * attempt in Processing, otherwise (for example in Preparing) a no-op.
     */
    fun stop() {
        val state = store.dispatch(CaptureEvent.TapStop)
        if (state.isCurrent(CaptureStatus.Processing)) enterStopPath()
    }

    /** Teardown. Sends no state event; the clip is discarded. */
    fun cancel() {
        if (outcomeClaimed.compareAndSet(false, true)) mutableResult.complete(CaptureResult.Cancelled)
        cancelTimers()
        enterStopPath()
    }

    // Controller side.

    private fun onPreparingTimeout() {
        val state = store.dispatch(CaptureEvent.PreparingTimedOut(attempt))
        if (!state.isCurrent(CaptureStatus.Failed(FailureReason.PreparingTimeout))) return
        log("attempt=$attempt preparing timed out after $PREPARING_TIMEOUT_MS ms")
        if (outcomeClaimed.compareAndSet(false, true)) {
            mutableResult.complete(CaptureResult.Failed(FailureReason.PreparingTimeout))
        }
        enterStopPath()
    }

    private fun onClockLimit() {
        val state = store.dispatch(CaptureEvent.LimitReached(attempt))
        if (!state.isCurrent(CaptureStatus.Processing)) return
        log("attempt=$attempt limit reached by clock")
        enterStopPath()
    }

    /** Stops the reader, then waits up to the bound for its loop to exit. */
    private fun enterStopPath() {
        if (!stopPathEntered.compareAndSet(false, true)) return
        stopRequested.set(true)
        stopSource()
        scope.launch {
            val exited = withTimeoutOrNull(READER_EXIT_BOUND_MS) { loopExited.await() } != null
            if (exited) return@launch
            reportedStuck = true
            log(
                "attempt=$attempt reader not exited $READER_EXIT_BOUND_MS ms after stop; " +
                    "source not released (possible leak)",
            )
            if (outcomeClaimed.compareAndSet(false, true)) publish(CaptureResult.Failed(FailureReason.ReadError))
        }
    }

    private fun cancelTimers() {
        preparingTimer?.cancel()
        limitTimer?.cancel()
    }

    // Reader side.

    private fun runReader() {
        var outcome: CaptureResult? = null
        try {
            val failure = try {
                readLoop()
            } catch (e: Exception) {
                log("attempt=$attempt reader failed: ${e.javaClass.simpleName}")
                FailureReason.ReadError
            }
            val claimed = outcomeClaimed.compareAndSet(false, true)
            loopExited.complete(Unit)
            if (claimed) {
                outcome = finishClip(failure)
            } else {
                writer?.abort()
            }
        } finally {
            releaseSource()
        }
        cancelTimers()
        if (reportedStuck) log("attempt=$attempt reader exited late; source released")
        outcome?.let(::publish)
    }

    /** Returns null on a normal exit (stop requested or sample cap). */
    private fun readLoop(): FailureReason? {
        if (stopRequested.get()) return null
        try {
            source.start()
        } catch (e: Exception) {
            log("attempt=$attempt source start failed: ${e.javaClass.simpleName}")
            return FailureReason.InitFailed
        }
        val out = try {
            WavWriter(outputFile)
        } catch (e: IOException) {
            log("attempt=$attempt write failed: ${e.javaClass.simpleName}")
            return FailureReason.ReadError
        }
        writer = out

        val buffer = ShortArray(CHUNK_SAMPLES)
        var total = 0
        var firstAudio = false
        var loggedEmptyRead = false
        while (!stopRequested.get()) {
            val n = source.read(buffer, 0, minOf(CHUNK_SAMPLES, MAX_SAMPLES - total))
            when {
                n > 0 -> {
                    try {
                        out.write(buffer, n)
                    } catch (e: IOException) {
                        log("attempt=$attempt write failed: ${e.javaClass.simpleName}")
                        return FailureReason.ReadError
                    }
                    detector.accept(buffer, n)
                    total += n
                    if (!firstAudio) {
                        firstAudio = true
                        onFirstAudio()
                    }
                    if (total >= MAX_SAMPLES) {
                        store.dispatch(CaptureEvent.LimitReached(attempt))
                        log("attempt=$attempt limit reached by sample count")
                        return null
                    }
                }
                // After a stop request any return value is a normal exit.
                stopRequested.get() -> return null
                n < 0 -> {
                    log("attempt=$attempt read failed: code $n")
                    return FailureReason.ReadError
                }
                else -> {
                    if (!loggedEmptyRead) {
                        loggedEmptyRead = true
                        log("attempt=$attempt read returned 0; waiting $EMPTY_READ_SLEEP_MS ms between reads")
                    }
                    Thread.sleep(EMPTY_READ_SLEEP_MS)
                }
            }
        }
        return null
    }

    private fun onFirstAudio() {
        val atMs = clock()
        val state = store.dispatch(CaptureEvent.FirstAudio(attempt, atMs))
        if (!state.isCurrent(CaptureStatus.Recording(atMs))) return
        preparingTimer?.cancel()
        log("attempt=$attempt first data ${atMs - startedAtMs} ms after start")
        val deadline = atMs + MAX_DURATION_MS
        limitTimer = scope.launch {
            delay(deadline - clock())
            onClockLimit()
        }
    }

    /** Finalises the clip after the loop has exited; reader thread only. */
    private fun finishClip(failure: FailureReason?): CaptureResult {
        val out = writer
        if (failure != null) {
            out?.abort()
            return CaptureResult.Failed(failure)
        }
        if (out == null) return CaptureResult.Failed(FailureReason.NoAudio)
        try {
            out.finish()
        } catch (e: IOException) {
            log("attempt=$attempt write failed: ${e.javaClass.simpleName}")
            out.abort()
            return CaptureResult.Failed(FailureReason.ReadError)
        }
        val firstLoud = detector.firstLoudFrameIndex
        log(
            if (firstLoud == null) {
                "attempt=$attempt no non-silent frame"
            } else {
                "attempt=$attempt first non-silent frame ${firstLoud * SilenceDetector.FRAME_MS} ms after first data"
            },
        )
        log(String.format(Locale.ROOT, "attempt=%d loudest frame %.1f dBFS", attempt, detector.loudestFrameDbfs))
        if (detector.isSilent) {
            out.abort()
            return CaptureResult.Failed(FailureReason.NoAudio)
        }
        return CaptureResult.Saved(outputFile)
    }

    private fun publish(outcome: CaptureResult) {
        when (outcome) {
            is CaptureResult.Saved -> store.dispatch(CaptureEvent.CaptureSaved(attempt))
            is CaptureResult.Failed -> store.dispatch(CaptureEvent.CaptureFailed(attempt, outcome.reason))
            CaptureResult.Cancelled -> Unit
        }
        mutableResult.complete(outcome)
    }

    /** Skipped once release has started; serialised with [releaseSource]. */
    private fun stopSource() {
        synchronized(sourceLock) {
            if (released) return
            try {
                source.stop()
            } catch (e: Exception) {
                log("attempt=$attempt source stop failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun releaseSource(): Unit = synchronized(sourceLock) {
        released = true
        try {
            source.release()
        } catch (e: Exception) {
            log("attempt=$attempt source release failed: ${e.javaClass.simpleName}")
        }
    }

    private fun CaptureState.isCurrent(expected: CaptureStatus): Boolean =
        this.attempt == this@CaptureSession.attempt && status == expected

    companion object {
        const val READER_THREAD_NAME = "HearCopyCaptureReader"
        const val CHUNK_SAMPLES = WavWriter.SAMPLE_RATE / 10
        const val MAX_DURATION_MS = 300_000L
        const val MAX_SAMPLES = (WavWriter.SAMPLE_RATE * MAX_DURATION_MS / 1000).toInt()
        const val PREPARING_TIMEOUT_MS = 2_000L
        const val READER_EXIT_BOUND_MS = 1_000L
        const val EMPTY_READ_SLEEP_MS = 10L
    }
}
