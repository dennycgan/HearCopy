package com.dennycgan.hearcopy.capture

import com.dennycgan.hearcopy.data.ApiKeyRead
import com.dennycgan.hearcopy.state.CaptureEvent
import com.dennycgan.hearcopy.state.CaptureState
import com.dennycgan.hearcopy.state.FailureReason
import com.dennycgan.hearcopy.transcription.TranscriptionError
import com.dennycgan.hearcopy.transcription.TranscriptionResult
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** The transcription notification of the current clip, as the upload sees it. */
interface TranscriptNotices {
    /** A new clip is being transcribed: replaces any earlier notification and alerts once. */
    fun transcribing()

    /** Updates the current notification in place. */
    fun completed()

    /** Updates the current notification in place with [reason]'s fixed text. */
    fun failed(reason: FailureReason)

    fun cancel()
}

/**
 * Uploads one saved clip (ADR-014). Runs on the main thread, in a job that
 * a teardown cancels.
 *
 * Order: notification, key read right before the upload, one upload, then
 * the commit point: the transcript write (on [io]) and the TranscriptReady
 * dispatch (back on the caller's thread) in one NonCancellable block. A
 * cancellation before the commit point discards the result and the
 * notification. The plaintext key lives only in this call.
 *
 * [disposeClip] receives the clip with keep = true only when its upload
 * was attempted and a result arrived (debug retention); every other clip
 * is deleted.
 */
class TranscriptionRun(
    private val readKey: suspend () -> ApiKeyRead,
    private val transcribe: suspend (File, String) -> TranscriptionResult,
    private val saveTranscript: suspend (String) -> Boolean,
    private val dispatch: (CaptureEvent) -> CaptureState,
    private val notices: TranscriptNotices,
    private val disposeClip: suspend (clip: File, keep: Boolean) -> Unit,
    /** Ends the session: the stored key can never be read again. */
    private val onKeyUnreadable: () -> Unit,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
    private val log: (String) -> Unit,
) {

    /** [stoppedAtMs]: when the attempt entered Processing, on [clock]. */
    suspend fun run(attempt: Int, clip: File, stoppedAtMs: Long) {
        var finished = false
        var keep = false
        try {
            val bytes = clip.length()
            log("attempt=$attempt clip ${clipDurationMs(bytes)} ms ($bytes bytes)")
            notices.transcribing()
            when (val read = readKey()) {
                is ApiKeyRead.Key -> {
                    val result = upload(attempt, clip, read.value)
                    keep = result.uploadAttempted()
                    log("attempt=$attempt result ${clock() - stoppedAtMs} ms after stop")
                    when (result) {
                        // The commit point. Not cancellable: once the write starts, the
                        // transcript is stored and shown even if a teardown arrives meanwhile.
                        is TranscriptionResult.Success -> withContext(NonCancellable) {
                            commit(attempt, result.text)
                            // Before the pending cancellation is rethrown on return.
                            finished = true
                        }
                        is TranscriptionResult.Failure -> fail(attempt, failureReasonOf(result.error))
                    }
                }
                ApiKeyRead.Absent, ApiKeyRead.Unavailable -> {
                    log("attempt=$attempt key read: $read; nothing uploaded")
                    fail(attempt, FailureReason.KeyUnavailable)
                }
                ApiKeyRead.Unreadable -> {
                    log("attempt=$attempt key read: Unreadable; nothing uploaded")
                    dispatch(CaptureEvent.ApiKeyReported(false))
                    // Before the teardown, so the alert is the only notification left.
                    notices.cancel()
                    onKeyUnreadable()
                }
            }
            finished = true
        } finally {
            withContext(NonCancellable) {
                if (!finished) {
                    log("attempt=$attempt upload cancelled")
                    notices.cancel()
                }
                disposeClip(clip, keep)
            }
        }
    }

    private suspend fun upload(attempt: Int, clip: File, key: String): TranscriptionResult {
        val started = clock()
        log("attempt=$attempt upload start")
        val result = transcribe(clip, key)
        // Success prints only its length; failures carry no message or body.
        log("attempt=$attempt upload done in ${clock() - started} ms: $result")
        return result
    }

    /**
     * Write on [io], then dispatch on the caller's thread. Called only in a
     * NonCancellable context. A failed write still shows the transcript; it
     * is lost at the next process start.
     */
    private suspend fun commit(attempt: Int, text: String) {
        val saved = withContext(io) { saveTranscript(text) }
        log(if (saved) "attempt=$attempt transcript saved" else "attempt=$attempt transcript save failed")
        val state = dispatch(CaptureEvent.TranscriptReady(attempt, text))
        // After a teardown during the write the notification was cancelled; leave it so.
        if (state.attempt == attempt) notices.completed()
    }

    private fun fail(attempt: Int, reason: FailureReason) {
        dispatch(CaptureEvent.TranscriptionFailed(attempt, reason))
        notices.failed(reason)
    }

    private fun TranscriptionResult.uploadAttempted(): Boolean =
        this !is TranscriptionResult.Failure ||
            (error != TranscriptionError.InvalidKey && error != TranscriptionError.TooLarge)

    companion object {
        private const val WAV_HEADER_BYTES = 44L

        /** 16 kHz, 16-bit mono: 32 bytes per millisecond. */
        private const val BYTES_PER_MS = 32L

        fun clipDurationMs(bytes: Long): Long = (bytes - WAV_HEADER_BYTES).coerceAtLeast(0) / BYTES_PER_MS
    }
}

/** The state's reason for an upload error. HTTP codes stay in the log. */
fun failureReasonOf(error: TranscriptionError): FailureReason = when (error) {
    // The stored key passed the same rule at save time; a refusal here
    // means the key cannot be used, as for a 401.
    TranscriptionError.InvalidKey -> FailureReason.Unauthorized
    TranscriptionError.TooLarge -> FailureReason.TooLarge
    TranscriptionError.Unauthorized -> FailureReason.Unauthorized
    TranscriptionError.Forbidden -> FailureReason.Forbidden
    TranscriptionError.RateLimited -> FailureReason.RateLimited
    TranscriptionError.QuotaExceeded -> FailureReason.QuotaExceeded
    TranscriptionError.Timeout -> FailureReason.Timeout
    is TranscriptionError.Network -> FailureReason.Network
    is TranscriptionError.Server -> FailureReason.Server
    is TranscriptionError.Rejected -> FailureReason.Rejected
    TranscriptionError.Malformed -> FailureReason.Malformed
    TranscriptionError.NoSpeech -> FailureReason.NoSpeech
}
