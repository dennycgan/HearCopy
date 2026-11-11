package com.dennycgan.hearcopy.capture

import com.dennycgan.hearcopy.data.ApiKeyRead
import com.dennycgan.hearcopy.state.CaptureEvent
import com.dennycgan.hearcopy.state.CaptureState
import com.dennycgan.hearcopy.state.CaptureStateStore
import com.dennycgan.hearcopy.state.CaptureStatus
import com.dennycgan.hearcopy.state.FailureReason
import com.dennycgan.hearcopy.state.MIN_CLIP_MS
import com.dennycgan.hearcopy.transcription.TranscriptionError
import com.dennycgan.hearcopy.transcription.TranscriptionResult
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The upload of one saved clip (ADR-014), with fakes for storage, client and
 * notification. The run executes on a "test-main" thread and gets a
 * "test-io" dispatcher, so the commit point's threading is observable.
 */
class TranscriptionRunTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val fakeKey = "sk-test-fake"
    private val text = "a private sentence from the clip"

    private lateinit var main: ExecutorCoroutineDispatcher
    private lateinit var io: ExecutorCoroutineDispatcher
    private lateinit var clip: File

    private val store = CaptureStateStore()

    /** Everything the run did, in order, from every fake. */
    private val timeline: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val events: MutableList<CaptureEvent> = Collections.synchronizedList(mutableListOf())

    private var keyRead: ApiKeyRead = ApiKeyRead.Key(fakeKey)
    private var upload: suspend (File, String) -> TranscriptionResult = { _, _ -> TranscriptionResult.Success(text) }
    private var save: suspend (String) -> Boolean = { true }
    private var transcribedWith: String? = null
    private var saveThread: String? = null
    private var readyThread: String? = null
    private var disposed: Pair<File, Boolean>? = null
    private var teardowns = 0
    private var job: Job? = null

    @Before
    fun setUp() {
        main = Executors.newSingleThreadExecutor { Thread(it, "test-main") }.asCoroutineDispatcher()
        io = Executors.newSingleThreadExecutor { Thread(it, "test-io") }.asCoroutineDispatcher()
        clip = temp.newFile("attempt-1.wav").apply { writeBytes(ByteArray(44 + 32_000)) }
        // Attempt 1 stopped after 1 s of audio: Processing.
        store.dispatch(CaptureEvent.SessionStarted)
        store.dispatch(CaptureEvent.TapStart)
        store.dispatch(CaptureEvent.FirstAudio(attempt = 1, atMs = 0))
        store.dispatch(CaptureEvent.TapStop(atMs = MIN_CLIP_MS))
        check(store.state.value.status == CaptureStatus.Processing)
    }

    @After
    fun tearDown() {
        main.close()
        io.close()
        // Key and transcript hygiene for every scenario.
        logs.forEach {
            assertFalse("key in log: $it", it.contains(fakeKey))
            assertFalse("transcript in log: $it", it.contains("private"))
        }
    }

    private val notices = object : TranscriptNotices {
        override fun transcribing() { timeline += "notice:transcribing" }
        override fun completed() { timeline += "notice:completed" }
        override fun failed(reason: FailureReason) { timeline += "notice:failed:$reason" }
        override fun cancel() { timeline += "notice:cancel" }
    }

    private fun newRun() = TranscriptionRun(
        readKey = {
            timeline += "readKey"
            keyRead
        },
        transcribe = { file, key ->
            timeline += "upload"
            transcribedWith = key
            upload(file, key)
        },
        saveTranscript = {
            timeline += "save"
            saveThread = threadName()
            save(it)
        },
        dispatch = { event ->
            events += event
            if (event is CaptureEvent.TranscriptReady) readyThread = threadName()
            timeline += "dispatch:${event.javaClass.simpleName}"
            store.dispatch(event)
        },
        notices = notices,
        disposeClip = { file, keep ->
            timeline += "dispose:keep=$keep"
            disposed = file to keep
        },
        onKeyUnreadable = {
            // As the service's teardown: once per instance, cancels the job
            // and the transcript notification, ends the session.
            timeline += "teardown:KeyUnreadable"
            teardowns++
            job?.cancel()
            notices.cancel()
            store.dispatch(CaptureEvent.SessionEnded)
        },
        io = io,
        clock = { 5_000L },
        log = { logs += it },
    )

    /** Starts the run on test-main, as the service does on the main thread. */
    private fun start(): Job = CoroutineScope(main).launch { newRun().run(attempt = currentAttempt, clip = clip, stoppedAtMs = 1_000L) }
        .also { job = it }

    private fun runToEnd() = runBlocking { withTimeout(5_000) { start().join() } }

    private val state: CaptureState get() = store.state.value

    // Success and the commit point

    @Test
    fun `a transcript is written on io, then dispatched on main, then the notification completes`() {
        runToEnd()

        assertEquals(
            listOf(
                "notice:transcribing", "readKey", "upload", "save",
                "dispatch:TranscriptReady", "notice:completed", "dispose:keep=true",
            ),
            timeline,
        )
        assertEquals("test-io", saveThread)
        assertEquals("test-main", readyThread)
        assertEquals(fakeKey, transcribedWith)
        assertEquals(CaptureStatus.Completed, state.status)
        assertEquals(text, state.lastTranscript)
        assertEquals(clip to true, disposed)
        assertTrue(logs.any { it.contains("attempt=1 clip 1000 ms (32044 bytes)") })
        assertTrue(logs.any { it.contains("attempt=1 result 4000 ms after stop") })
        assertTrue(logs.any { it.contains("upload done in 0 ms: Success(length=${text.length})") })
        assertTrue(logs.any { it.contains("transcript saved") })
    }

    @Test
    fun `a failed write still shows the transcript and logs save failed`() {
        save = { false }
        runToEnd()

        assertEquals(CaptureStatus.Completed, state.status)
        assertEquals(text, state.lastTranscript)
        assertTrue(logs.any { it.contains("attempt=1 transcript save failed") })
        assertTrue(timeline.contains("notice:completed"))
    }

    @Test
    fun `a teardown during the write keeps the transcript and posts no completion`() = runBlocking {
        val writing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        save = {
            writing.complete(Unit)
            release.await()
            true
        }
        val running = start()
        withTimeout(5_000) { writing.await() }
        // The service's teardown on main: cancel the job, cancel the notification, end the session.
        CoroutineScope(main).launch {
            running.cancel()
            notices.cancel()
            store.dispatch(CaptureEvent.SessionEnded)
        }.join()
        release.complete(Unit)
        withTimeout(5_000) { running.join() }

        assertEquals("test-io", saveThread)
        assertEquals("test-main", readyThread)
        assertEquals(text, state.lastTranscript)
        assertEquals(CaptureStatus.Ready, state.status)
        assertEquals(2, state.attempt)
        assertFalse(timeline.contains("notice:completed"))
        assertFalse(logs.any { it.contains("upload cancelled") })
        assertEquals(clip to true, disposed)
    }

    // Upload failures

    @Test
    fun `every upload error fails the attempt with its reason and keeps the old transcript`() {
        val errors = listOf(
            TranscriptionError.Unauthorized, TranscriptionError.Forbidden, TranscriptionError.RateLimited,
            TranscriptionError.QuotaExceeded, TranscriptionError.Timeout, TranscriptionError.Network("SocketException"),
            TranscriptionError.Server(503), TranscriptionError.Rejected(413), TranscriptionError.Malformed,
            TranscriptionError.NoSpeech, TranscriptionError.InvalidKey, TranscriptionError.TooLarge,
        )
        errors.forEach { error ->
            setUpFresh()
            store.dispatch(CaptureEvent.TranscriptRestored("old"))
            upload = { _, _ -> TranscriptionResult.Failure(error) }
            runToEnd()

            val reason = failureReasonOf(error)
            assertEquals("$error", CaptureStatus.Failed(reason), state.status)
            assertEquals("$error", "old", state.lastTranscript)
            assertEquals("$error", "notice:failed:$reason", timeline[timeline.size - 2])
            // Only clips the API received are kept for debug listening.
            val sent = error != TranscriptionError.InvalidKey && error != TranscriptionError.TooLarge
            assertEquals("$error", clip to sent, disposed)
        }
    }

    @Test
    fun `upload errors map to reasons without codes`() {
        assertEquals(FailureReason.Unauthorized, failureReasonOf(TranscriptionError.Unauthorized))
        assertEquals(FailureReason.Unauthorized, failureReasonOf(TranscriptionError.InvalidKey))
        assertEquals(FailureReason.Forbidden, failureReasonOf(TranscriptionError.Forbidden))
        assertEquals(FailureReason.RateLimited, failureReasonOf(TranscriptionError.RateLimited))
        assertEquals(FailureReason.QuotaExceeded, failureReasonOf(TranscriptionError.QuotaExceeded))
        assertEquals(FailureReason.Timeout, failureReasonOf(TranscriptionError.Timeout))
        assertEquals(FailureReason.Network, failureReasonOf(TranscriptionError.Network("SocketException")))
        assertEquals(FailureReason.Server, failureReasonOf(TranscriptionError.Server(500)))
        assertEquals(FailureReason.Rejected, failureReasonOf(TranscriptionError.Rejected(307)))
        assertEquals(FailureReason.Malformed, failureReasonOf(TranscriptionError.Malformed))
        assertEquals(FailureReason.NoSpeech, failureReasonOf(TranscriptionError.NoSpeech))
        assertEquals(FailureReason.TooLarge, failureReasonOf(TranscriptionError.TooLarge))
    }

    // Key read

    @Test
    fun `an absent or unavailable key fails this attempt only and deletes nothing`() {
        listOf(ApiKeyRead.Absent, ApiKeyRead.Unavailable).forEach { read ->
            setUpFresh()
            store.dispatch(CaptureEvent.ApiKeyReported(true))
            keyRead = read
            runToEnd()

            assertEquals(
                "$read",
                listOf(
                    "notice:transcribing", "readKey", "dispatch:TranscriptionFailed",
                    "notice:failed:KeyUnavailable", "dispose:keep=false",
                ),
                timeline,
            )
            assertEquals(CaptureStatus.Failed(FailureReason.KeyUnavailable), state.status)
            // No presence refresh: apiKeyPresent is left as it was.
            assertEquals(true, state.setup.apiKeyPresent)
            assertTrue(events.none { it is CaptureEvent.ApiKeyReported })
            assertEquals(0, teardowns)
        }
    }

    @Test
    fun `an unreadable key removes the transcript notification, then ends the session once`() {
        keyRead = ApiKeyRead.Unreadable
        runToEnd()

        assertEquals(
            listOf(
                "notice:transcribing", "readKey", "dispatch:ApiKeyReported",
                "notice:cancel", "teardown:KeyUnreadable", "notice:cancel", "dispose:keep=false",
            ),
            timeline,
        )
        assertEquals(1, teardowns)
        assertNull(transcribedWith)
        assertEquals(false, state.setup.apiKeyPresent)
        assertEquals(CaptureStatus.Ready, state.status)
        // Nothing posts a transcript notification after the teardown.
        val afterTeardown = timeline.drop(timeline.indexOf("teardown:KeyUnreadable") + 1)
        assertTrue(afterTeardown.none { it.startsWith("notice:") && it != "notice:cancel" })
        assertFalse(logs.any { it.contains("upload start") })
    }

    // Cancellation

    @Test
    fun `a cancel during the upload discards the result, the notification and the clip`() = runBlocking {
        val uploading = CompletableDeferred<Unit>()
        upload = { _, _ ->
            uploading.complete(Unit)
            CompletableDeferred<TranscriptionResult>().await()
        }
        val running = start()
        withTimeout(5_000) {
            uploading.await()
            running.cancelAndJoin()
        }

        assertEquals(
            listOf("notice:transcribing", "readKey", "upload", "notice:cancel", "dispose:keep=false"),
            timeline,
        )
        assertTrue(events.isEmpty())
        assertEquals(CaptureStatus.Processing, state.status)
        assertTrue(logs.any { it.contains("attempt=1 upload cancelled") })
    }

    /** Resets the fakes and the store's attempt for one more scenario in the same test. */
    private fun setUpFresh() {
        timeline.clear()
        events.clear()
        disposed = null
        transcribedWith = null
        store.dispatch(CaptureEvent.SessionEnded)
        store.dispatch(CaptureEvent.SessionStarted)
        val attempt = store.dispatch(CaptureEvent.TapStart).attempt
        store.dispatch(CaptureEvent.FirstAudio(attempt, atMs = 0))
        store.dispatch(CaptureEvent.TapStop(atMs = MIN_CLIP_MS))
        check(state.status == CaptureStatus.Processing)
        currentAttempt = attempt
    }

    private var currentAttempt = 1

    /** The thread's own name; coroutine debug mode appends " @coroutine#N". */
    private fun threadName(): String = Thread.currentThread().name.substringBefore(" @")
}
