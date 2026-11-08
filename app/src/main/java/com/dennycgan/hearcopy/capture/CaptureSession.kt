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
 * One capture attempt: the clip that [CaptureLoop] feeds between a tap and
 * its stop, streamed to [outputFile], and the attempt's state events.
 *
 * Rules (Cap 1 design B4 as mapped in 10.4):
 * - The controller side (timers, [stop], [cancel]) never touches the
 *   source; it only requests the end of the clip. The loop's reader detaches
 *   the clip at a chunk boundary and then calls [finishOnReader].
 * - WAV finalisation and the silence verdict run on the reader thread after
 *   the clip is detached, never concurrently with a write.
 * - One outcome per attempt: the first to claim the outcome flag decides.
 *   Preparing timeout and [cancel] claim it at once. Manual stop and the
 *   300 s limit leave it to the reader for [READER_EXIT_BOUND_MS]; if the
 *   clip has not been detached by then, it is Failed(ReadError).
 * - Racing events are arbitrated by the state returned from `dispatch`.
 *
 * [scope] runs only the timers; [clock] is monotonic milliseconds; [log] is
 * the diagnostics sink.
 */
class CaptureSession(
    private val attempt: Int,
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
    private val detached = CompletableDeferred<Unit>()

    @Volatile private var startedAtMs = 0L
    @Volatile private var preparingTimer: Job? = null
    @Volatile private var limitTimer: Job? = null
    @Volatile private var reportedStuck = false

    // Reader-thread state.
    private var writer: WavWriter? = null
    private val detector = SilenceDetector()
    private var failure: FailureReason? = null
    private var total = 0
    private var firstAudio = false
    private var capReached = false
    private var loggedEmptyRead = false

    /**
     * Starts the attempt's timers. Call once, on the main thread, right
     * after `TapStart` returned Preparing for [attempt] and before the clip
     * is attached.
     */
    fun start() {
        check(started.compareAndSet(false, true)) { "CaptureSession started twice" }
        startedAtMs = clock()
        val deadline = startedAtMs + PREPARING_TIMEOUT_MS
        preparingTimer = scope.launch {
            delay(deadline - clock())
            onPreparingTimeout()
        }
    }

    /**
     * Manual stop. Dispatches `TapStop`; continues only if that leaves this
     * attempt in Processing, otherwise (for example in Preparing) a no-op.
     * A session whose attempt is no longer current returns at once:
     * `TapStop` carries no attempt and would move the newer attempt instead.
     * Main thread only, like every attempt-changing dispatch.
     */
    fun stop() {
        if (store.state.value.attempt != attempt) return
        val state = store.dispatch(CaptureEvent.TapStop)
        if (state.isCurrent(CaptureStatus.Processing)) enterStopPath()
    }

    /** Teardown. Sends no state event; the clip is discarded. */
    fun cancel() {
        if (outcomeClaimed.compareAndSet(false, true)) mutableResult.complete(CaptureResult.Cancelled)
        cancelTimers()
        enterStopPath()
    }

    /** Fails an attempt that the loop refused to attach. Main thread. */
    fun failUnattached() {
        if (!outcomeClaimed.compareAndSet(false, true)) return
        cancelTimers()
        log("attempt=$attempt not attached: capture busy or ending")
        publish(CaptureResult.Failed(FailureReason.ReadError))
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

    /** Requests the end of the clip, then waits up to the bound for the reader to detach it. */
    private fun enterStopPath() {
        if (!stopPathEntered.compareAndSet(false, true)) return
        stopRequested.set(true)
        scope.launch {
            val done = withTimeoutOrNull(READER_EXIT_BOUND_MS) { detached.await() } != null
            if (done) return@launch
            reportedStuck = true
            log("attempt=$attempt reader not detached $READER_EXIT_BOUND_MS ms after stop; clip abandoned")
            if (outcomeClaimed.compareAndSet(false, true)) publish(CaptureResult.Failed(FailureReason.ReadError))
        }
    }

    private fun cancelTimers() {
        preparingTimer?.cancel()
        limitTimer?.cancel()
    }

    // Reader side: called only by CaptureLoop's reader thread.

    /** Whether [open] has run. */
    internal val isOpen: Boolean get() = writer != null || failure != null

    /** Whether the next read belongs to this clip. */
    internal val wantsAudio: Boolean get() = !stopRequested.get() && failure == null && !capReached

    /** Opens the WAV file; a failure ends the clip with ReadError. */
    internal fun open() {
        try {
            writer = WavWriter(outputFile)
        } catch (e: IOException) {
            log("attempt=$attempt write failed: ${e.javaClass.simpleName}")
            failure = FailureReason.ReadError
        }
    }

    internal fun readSize(): Int = minOf(CHUNK_SAMPLES, MAX_SAMPLES - total)

    /** Writes one chunk; [n] > 0. */
    internal fun accept(buffer: ShortArray, n: Int) {
        val out = writer ?: return
        try {
            out.write(buffer, n)
        } catch (e: IOException) {
            log("attempt=$attempt write failed: ${e.javaClass.simpleName}")
            failure = FailureReason.ReadError
            return
        }
        detector.accept(buffer, n)
        total += n
        if (!firstAudio) {
            firstAudio = true
            onFirstAudio()
        }
        if (total >= MAX_SAMPLES) {
            capReached = true
            store.dispatch(CaptureEvent.LimitReached(attempt))
            log("attempt=$attempt limit reached by sample count")
        }
    }

    internal fun emptyRead() {
        if (loggedEmptyRead) return
        loggedEmptyRead = true
        log("attempt=$attempt read returned 0; waiting ${CaptureLoop.EMPTY_READ_SLEEP_MS} ms between reads")
    }

    internal fun readFailed(code: Int) {
        log("attempt=$attempt read failed: code $code")
        failure = FailureReason.ReadError
    }

    internal fun readerFailed(e: Exception) {
        log("attempt=$attempt reader failed: ${e.javaClass.simpleName}")
        failure = FailureReason.ReadError
    }

    /** Decides and publishes the outcome after the clip was detached. */
    internal fun finishOnReader() {
        val claimed = outcomeClaimed.compareAndSet(false, true)
        detached.complete(Unit)
        var outcome: CaptureResult? = null
        if (claimed) {
            // Any unexpected exception here must still give the attempt an
            // outcome; an uncaught one would also stop the reader.
            outcome = try {
                finishClip(failure)
            } catch (e: Exception) {
                log("attempt=$attempt finalise failed: ${e.javaClass.simpleName}")
                abortQuietly()
                CaptureResult.Failed(FailureReason.ReadError)
            }
        } else {
            abortQuietly()
        }
        cancelTimers()
        if (reportedStuck) log("attempt=$attempt reader detached late; clip discarded")
        outcome?.let(::publish)
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
        try {
            when (outcome) {
                is CaptureResult.Saved -> store.dispatch(CaptureEvent.CaptureSaved(attempt))
                is CaptureResult.Failed -> store.dispatch(CaptureEvent.CaptureFailed(attempt, outcome.reason))
                CaptureResult.Cancelled -> Unit
            }
        } catch (e: Exception) {
            log("attempt=$attempt publish failed: ${e.javaClass.simpleName}")
        } finally {
            mutableResult.complete(outcome)
        }
    }

    /** Discards the clip; never throws. Reader thread only. */
    private fun abortQuietly() {
        try {
            writer?.abort()
        } catch (e: Exception) {
            log("attempt=$attempt discard failed: ${e.javaClass.simpleName}")
        }
    }

    private fun CaptureState.isCurrent(expected: CaptureStatus): Boolean =
        this.attempt == this@CaptureSession.attempt && status == expected

    companion object {
        const val CHUNK_SAMPLES = WavWriter.SAMPLE_RATE / 10
        const val MAX_DURATION_MS = 300_000L
        const val MAX_SAMPLES = (WavWriter.SAMPLE_RATE * MAX_DURATION_MS / 1000).toInt()
        const val PREPARING_TIMEOUT_MS = 2_000L
        const val READER_EXIT_BOUND_MS = 1_000L
    }
}
