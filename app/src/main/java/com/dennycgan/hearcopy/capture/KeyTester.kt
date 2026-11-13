package com.dennycgan.hearcopy.capture

import com.dennycgan.hearcopy.data.ApiKeyRead
import com.dennycgan.hearcopy.state.CaptureEvent
import com.dennycgan.hearcopy.state.CaptureStateStore
import com.dennycgan.hearcopy.state.FailureReason
import com.dennycgan.hearcopy.state.KeyTest
import com.dennycgan.hearcopy.transcription.TranscriptionError
import com.dennycgan.hearcopy.transcription.TranscriptionResult
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Settings key test (ADR-019): one upload of the built-in clip with the
 * stored key, its result held in the state store. Main thread only; runs
 * on the application-wide [scope], so it outlives the Activity.
 *
 * Not a capture: no notification, no debug retention, no transcript, no
 * minimum length, never retried (ADR-012). No response text reaches the
 * state or the log.
 */
class KeyTester(
    private val store: CaptureStateStore,
    private val readKey: suspend () -> ApiKeyRead,
    private val transcribe: suspend (File, String) -> TranscriptionResult,
    /** The built-in clip. */
    private val openClip: () -> InputStream,
    /** The temporary copy for [run]; unique per run, so a late delete of an old copy cannot hit a new one. */
    private val tempFile: (run: Int) -> File,
    /** Ends a running session without an alert, as a key delete does. */
    private val endSessionForKeyRemoved: () -> Unit,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val log: (String) -> Unit,
) {

    private var job: Job? = null
    private var jobRun = 0

    /** Starts a run; returns at once when one is already running. */
    fun start() {
        if (store.state.value.keyTest == KeyTest.Running) {
            log("key test ignored: already running")
            return
        }
        val run = store.dispatch(CaptureEvent.KeyTestStarted).keyTestRun
        // Lazy: on Main.immediate the run would start inline, before the job is known.
        val next = scope.launch(start = CoroutineStart.LAZY) { execute(run) }
        job = next
        jobRun = run
        next.invokeOnCompletion { if (job === next) job = null }
        next.start()
    }

    /** A key was saved or deleted: cancels a running test and resets the result. */
    fun reset() {
        job?.let {
            log("key test run=$jobRun cancelled: key changed")
            it.cancel()
        }
        job = null
        store.dispatch(CaptureEvent.KeyTestReset)
    }

    /**
     * A session teardown that ends the use (UserEnded, KeyRemoved,
     * KeyUnreadable): cancels a running test. A finished result stays.
     */
    fun cancel() {
        val running = job ?: return
        job = null
        log("key test run=$jobRun cancelled: session ended")
        running.cancel()
        store.dispatch(CaptureEvent.KeyTestCancelled(jobRun))
    }

    private suspend fun execute(run: Int) {
        log("key test run=$run start")
        val outcome: KeyTest.Finished = when (val read = readKey()) {
            is ApiKeyRead.Key -> upload(run, read.value)
            ApiKeyRead.Absent, ApiKeyRead.Unavailable -> {
                log("key test run=$run key read: $read; nothing uploaded")
                KeyTest.Failed(FailureReason.KeyUnavailable)
            }
            ApiKeyRead.Unreadable -> {
                // data/ has already removed blob, hint and Keystore key (ADR-013).
                log("key test run=$run key read: Unreadable; nothing uploaded")
                store.dispatch(CaptureEvent.ApiKeyReported(false))
                store.dispatch(CaptureEvent.KeyTestFinished(run, KeyTest.Unreadable))
                // The run is done; the teardown below must not count it as running.
                job = null
                // Directly, never through the delete command: its reset would
                // overwrite the result just committed.
                endSessionForKeyRemoved()
                return
            }
        }
        log("key test run=$run result: $outcome")
        store.dispatch(CaptureEvent.KeyTestFinished(run, outcome))
    }

    private suspend fun upload(run: Int, key: String): KeyTest.Finished {
        val clip = tempFile(run)
        try {
            try {
                withContext(io) {
                    clip.parentFile?.mkdirs()
                    openClip().use { input -> clip.outputStream().use { input.copyTo(it) } }
                }
            } catch (e: IOException) {
                // A local file failure, as for a capture (CaptureSession).
                log("key test run=$run clip copy failed: ${e.javaClass.simpleName}; nothing uploaded")
                return KeyTest.Failed(FailureReason.ReadError)
            }
            log("key test run=$run upload start")
            val result = transcribe(clip, key)
            // Never the transcript, not even its length.
            log("key test run=$run upload done: ${if (result is TranscriptionResult.Failure) result else "Success"}")
            return when (result) {
                is TranscriptionResult.Success -> KeyTest.Passed
                // A blank transcript still proves the key works.
                is TranscriptionResult.Failure ->
                    if (result.error == TranscriptionError.NoSpeech) KeyTest.Passed else KeyTest.Failed(failureReasonOf(result.error))
            }
        } finally {
            withContext(NonCancellable + io) {
                if (!clip.delete() && clip.exists()) log("key test run=$run clip could not be deleted")
            }
        }
    }
}
