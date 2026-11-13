package com.dennycgan.hearcopy.capture

import com.dennycgan.hearcopy.data.ApiKeyRead
import com.dennycgan.hearcopy.state.CaptureEvent
import com.dennycgan.hearcopy.state.CaptureState
import com.dennycgan.hearcopy.state.CaptureStateStore
import com.dennycgan.hearcopy.state.FailureReason
import com.dennycgan.hearcopy.state.KeyTest
import com.dennycgan.hearcopy.transcription.TranscriptionError
import com.dennycgan.hearcopy.transcription.TranscriptionResult
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Settings key test (ADR-019) with fakes for storage, client and the
 * session handle. The tester runs on a "test-main" thread, as on the
 * device's main thread.
 */
class KeyTesterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val fakeKey = "sk-test-fake"
    private val text = "a private sentence from the response"
    private val clipBytes = ByteArray(44 + 32_000) { (it % 251).toByte() }

    private lateinit var main: ExecutorCoroutineDispatcher
    private lateinit var io: ExecutorCoroutineDispatcher
    private lateinit var scope: CoroutineScope
    private lateinit var tester: KeyTester

    private val store = CaptureStateStore()
    private val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private var keyRead: ApiKeyRead = ApiKeyRead.Key(fakeKey)
    private var result: suspend () -> TranscriptionResult = { TranscriptionResult.Success(text) }
    private var openClip: () -> InputStream = { ByteArrayInputStream(clipBytes) }
    private var uploads = 0
    private var uploadedWith: String? = null
    private var uploadedBytes: ByteArray? = null
    private var uploadedFile: File? = null
    private val uploadStarted = CompletableDeferred<Unit>()
    private var uploadCancelled = false
    private var stateAtSessionEnd: CaptureState? = null
    private var sessionEnds = 0

    @Before
    fun setUp() {
        main = Executors.newSingleThreadExecutor { Thread(it, "test-main") }.asCoroutineDispatcher()
        io = Executors.newSingleThreadExecutor { Thread(it, "test-io") }.asCoroutineDispatcher()
        scope = CoroutineScope(SupervisorJob() + main)
        store.dispatch(CaptureEvent.ApiKeyReported(true))
        tester = KeyTester(
            store = store,
            readKey = { keyRead },
            transcribe = { file, key ->
                uploads++
                uploadedWith = key
                uploadedFile = file
                uploadedBytes = file.readBytes()
                uploadStarted.complete(Unit)
                try {
                    result()
                } catch (e: CancellationException) {
                    uploadCancelled = true
                    throw e
                }
            },
            openClip = { openClip() },
            tempFile = { File(temp.root, "capture/test-$it.wav") },
            endSessionForKeyRemoved = {
                sessionEnds++
                stateAtSessionEnd = store.state.value
                // The service's teardown (KeyRemoved) cancels a running test.
                tester.cancel()
            },
            scope = scope,
            io = io,
            log = { logs += it },
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        main.close()
        io.close()
        logs.forEach {
            assertFalse("key in log: $it", it.contains(fakeKey))
            assertFalse("response text in log: $it", it.contains("private"))
            assertFalse("response length in log: $it", it.contains("length"))
        }
        // No temporary copy outlives its run.
        assertEquals(emptyList<String>(), File(temp.root, "capture").list()?.toList().orEmpty())
    }

    private fun onMain(block: () -> Unit) = runBlocking(main) { block() }

    private fun awaitState(predicate: (CaptureState) -> Boolean): CaptureState =
        runBlocking { withTimeout(5_000) { store.state.first(predicate) } }

    private fun runToResult(): KeyTest {
        onMain { tester.start() }
        return awaitState { it.keyTest is KeyTest.Finished }.keyTest
    }

    @Test
    fun `a successful upload of the built-in clip passes`() {
        assertEquals(KeyTest.Passed, runToResult())
        assertEquals(1, uploads)
        assertEquals(fakeKey, uploadedWith)
        assertArrayEquals(clipBytes, uploadedBytes)
        assertEquals(File(temp.root, "capture/test-1.wav"), uploadedFile)
        assertEquals(1, store.state.value.keyTestRun)
    }

    @Test
    fun `the test leaves the transcript, the key and the session alone`() {
        store.dispatch(CaptureEvent.TranscriptRestored("kept"))
        runToResult()
        assertEquals("kept", store.state.value.lastTranscript)
        assertEquals(true, store.state.value.setup.apiKeyPresent)
        assertEquals(0, sessionEnds)
    }

    @Test
    fun `a blank transcript passes`() {
        result = { TranscriptionResult.Failure(TranscriptionError.NoSpeech) }
        assertEquals(KeyTest.Passed, runToResult())
    }

    @Test
    fun `an upload error fails with its reason`() {
        mapOf(
            TranscriptionError.Unauthorized to FailureReason.Unauthorized,
            TranscriptionError.InvalidKey to FailureReason.Unauthorized,
            TranscriptionError.QuotaExceeded to FailureReason.QuotaExceeded,
            TranscriptionError.Network("SocketException") to FailureReason.Network,
        ).forEach { (error, reason) ->
            result = { TranscriptionResult.Failure(error) }
            assertEquals("$error", KeyTest.Failed(reason), runToResult())
        }
        assertEquals(4, uploads)
    }

    @Test
    fun `an absent or unavailable key fails as KeyUnavailable without an upload`() {
        listOf(ApiKeyRead.Absent, ApiKeyRead.Unavailable).forEach { read ->
            keyRead = read
            assertEquals("$read", KeyTest.Failed(FailureReason.KeyUnavailable), runToResult())
        }
        assertEquals(0, uploads)
        assertEquals(true, store.state.value.setup.apiKeyPresent)
        assertEquals(0, sessionEnds)
    }

    @Test
    fun `an unreadable key is reported absent and committed before the session ends`() {
        keyRead = ApiKeyRead.Unreadable
        assertEquals(KeyTest.Unreadable, runToResult())
        assertEquals(0, uploads)
        assertEquals(1, sessionEnds)
        val atEnd = stateAtSessionEnd!!
        assertEquals(false, atEnd.setup.apiKeyPresent)
        assertEquals(KeyTest.Unreadable, atEnd.keyTest)
        // The teardown's cancel found no running test: the result stays.
        assertEquals(KeyTest.Unreadable, store.state.value.keyTest)
    }

    @Test
    fun `a start while a test is running does nothing`() {
        val gate = CompletableDeferred<TranscriptionResult>()
        result = { gate.await() }
        onMain { tester.start() }
        runBlocking { withTimeout(5_000) { uploadStarted.await() } }
        onMain { tester.start() }
        assertEquals(1, store.state.value.keyTestRun)
        assertEquals(KeyTest.Running, store.state.value.keyTest)
        gate.complete(TranscriptionResult.Success(text))
        awaitState { it.keyTest == KeyTest.Passed }
        assertEquals(1, uploads)
    }

    @Test
    fun `a reset cancels the running test and returns to NotTested`() {
        result = { CompletableDeferred<TranscriptionResult>().await() }
        onMain { tester.start() }
        runBlocking { withTimeout(5_000) { uploadStarted.await() } }
        onMain { tester.reset() }
        assertEquals(KeyTest.NotTested, store.state.value.keyTest)
        assertEquals(2, store.state.value.keyTestRun)
        awaitCancelledUpload()
    }

    @Test
    fun `a reset after a result clears it`() {
        runToResult()
        onMain { tester.reset() }
        assertEquals(KeyTest.NotTested, store.state.value.keyTest)
    }

    @Test
    fun `a session teardown cancels the running test`() {
        result = { CompletableDeferred<TranscriptionResult>().await() }
        onMain { tester.start() }
        runBlocking { withTimeout(5_000) { uploadStarted.await() } }
        onMain { tester.cancel() }
        assertEquals(KeyTest.NotTested, store.state.value.keyTest)
        assertEquals(1, store.state.value.keyTestRun)
        awaitCancelledUpload()
    }

    @Test
    fun `a session teardown keeps a finished result`() {
        result = { TranscriptionResult.Failure(TranscriptionError.Forbidden) }
        runToResult()
        onMain { tester.cancel() }
        assertEquals(KeyTest.Failed(FailureReason.Forbidden), store.state.value.keyTest)
    }

    @Test
    fun `a clip that cannot be copied fails without an upload`() {
        openClip = { throw IOException("disk full") }
        assertEquals(KeyTest.Failed(FailureReason.ReadError), runToResult())
        assertEquals(0, uploads)
        assertNull(uploadedFile)
    }

    /** The cancelled upload threw, and its finally block deleted the copy. */
    private fun awaitCancelledUpload() {
        runBlocking {
            withTimeout(5_000) {
                while (!uploadCancelled || File(temp.root, "capture").list()?.isNotEmpty() == true) {
                    delay(10)
                }
            }
        }
        assertTrue(uploadCancelled)
    }
}
