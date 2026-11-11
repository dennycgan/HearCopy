package com.dennycgan.hearcopy.transcription

import com.dennycgan.hearcopy.transcription.TranscriptionError.Forbidden
import com.dennycgan.hearcopy.transcription.TranscriptionError.InvalidKey
import com.dennycgan.hearcopy.transcription.TranscriptionError.Malformed
import com.dennycgan.hearcopy.transcription.TranscriptionError.Network
import com.dennycgan.hearcopy.transcription.TranscriptionError.NoSpeech
import com.dennycgan.hearcopy.transcription.TranscriptionError.QuotaExceeded
import com.dennycgan.hearcopy.transcription.TranscriptionError.RateLimited
import com.dennycgan.hearcopy.transcription.TranscriptionError.Rejected
import com.dennycgan.hearcopy.transcription.TranscriptionError.Server
import com.dennycgan.hearcopy.transcription.TranscriptionError.Timeout
import com.dennycgan.hearcopy.transcription.TranscriptionError.TooLarge
import com.dennycgan.hearcopy.transcription.TranscriptionError.Unauthorized
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartReader
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TranscriptionClientTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val server = MockWebServer()

    @Before
    fun startServer() {
        server.start()
    }

    @After
    fun closeServer() {
        server.close()
    }

    private fun client(callTimeoutMillis: Long = TranscriptionClient.DEFAULT_CALL_TIMEOUT_MS) =
        TranscriptionClient(server.url("/"), callTimeoutMillis)

    private fun clip(): File = temp.newFile("attempt-1.wav").apply { writeBytes(CLIP_BYTES) }

    private fun transcribe(
        file: File,
        key: String = FAKE_KEY,
        client: TranscriptionClient = client(),
    ): TranscriptionResult = runBlocking { withTimeout(10_000) { client.transcribe(file, key) } }

    private fun respond(code: Int, body: String = "", configure: MockResponse.Builder.() -> Unit = {}) {
        server.enqueue(MockResponse.Builder().code(code).body(body).apply(configure).build())
    }

    private fun failure(error: TranscriptionError) = TranscriptionResult.Failure(error)

    // Configuration (ADR-012)

    @Test
    fun `production client uses the approved timeouts and never re-sends`() {
        val http = TranscriptionClient().httpClient
        assertEquals(15_000, http.connectTimeoutMillis)
        assertEquals(0, http.readTimeoutMillis)
        assertEquals(0, http.writeTimeoutMillis)
        assertEquals(120_000, http.callTimeoutMillis)
        assertFalse(http.retryOnConnectionFailure)
        assertFalse(http.followRedirects)
    }

    @Test
    fun `production client posts to the OpenAI endpoint`() {
        assertEquals(
            "https://api.openai.com/v1/audio/transcriptions",
            TranscriptionClient().endpoint.toString(),
        )
    }

    // Request shape

    @Test
    fun `request is one multipart POST with model, languages and the clip`() {
        respond(200, """{"text":"hello"}""")
        transcribe(clip())

        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/audio/transcriptions", request.url.encodedPath)
        assertEquals("Bearer $FAKE_KEY", request.headers["Authorization"])
        assertEquals(request.bodySize.toString(), request.headers["Content-Length"])

        val parts = formParts(request)
        assertEquals(listOf("model", "languages[]", "file"), parts.map { it.name })
        assertFalse(parts.any { it.name == "language" })
        assertEquals("gpt-transcribe", parts[0].bytes.decodeToString())
        assertEquals("en", parts[1].bytes.decodeToString())
        val file = parts[2]
        assertEquals("attempt-1.wav", file.filename)
        assertEquals("audio/wav", file.contentType)
        assertArrayEquals(CLIP_BYTES, file.bytes)
    }

    // Successful responses

    @Test
    fun `text is returned`() {
        respond(200, """{"text":"Hello there.","languages":["en"]}""")
        assertEquals(TranscriptionResult.Success("Hello there."), transcribe(clip()))
    }

    @Test
    fun `blank text is no speech`() {
        respond(200, """{"text":"  \n"}""")
        assertEquals(failure(NoSpeech), transcribe(clip()))
    }

    @Test
    fun `null, missing, non-string text or a non-object body is malformed`() {
        val file = clip()
        listOf(
            """{"text":null}""",
            """{"other":"x"}""",
            """{"text":42}""",
            """{"text":{"value":"x"}}""",
            """["text"]""",
            "not json",
            "",
        ).forEach { body ->
            respond(200, body)
            assertEquals(body, failure(Malformed), transcribe(file))
        }
    }

    @Test
    fun `success toString does not show the transcript`() {
        assertFalse(TranscriptionResult.Success("secret words").toString().contains("secret"))
    }

    // Status codes

    @Test
    fun `status codes map to errors`() {
        val file = clip()
        listOf(
            401 to Unauthorized,
            403 to Forbidden,
            400 to Rejected(400),
            404 to Rejected(404),
            413 to Rejected(413),
            415 to Rejected(415),
            500 to Server(500),
            502 to Server(502),
        ).forEach { (code, expected) ->
            respond(code, errorBody(code = null))
            assertEquals("HTTP $code", failure(expected), transcribe(file))
        }
    }

    @Test
    fun `429 without a billing signal is rate limited`() {
        val file = clip()
        listOf(
            errorBody(code = null),
            """{"error":{"type":"requests","code":null}}""",
            errorBody(code = "slow_down"),
            errorBody(code = "something_new"),
            """{"error":{"code":7}}""",
            "not json",
        ).forEach { body ->
            respond(429, body)
            assertEquals(body, failure(RateLimited), transcribe(file))
        }
    }

    @Test
    fun `429 with a billing signal is quota exceeded`() {
        val file = clip()
        listOf(
            errorBody(code = null, type = "insufficient_quota"),
            errorBody(code = "project_spend_limit_exceeded"),
            errorBody(code = "credit_balance_exhausted"),
            errorBody(code = "organization_usage_limit_exceeded"),
        ).forEach { body ->
            respond(429, body)
            assertEquals(body, failure(QuotaExceeded), transcribe(file))
        }
    }

    // No silent re-send (each case queues a 200 that a re-send would receive)

    @Test
    fun `503 with Retry-After 0 is not sent again`() {
        respond(503) { addHeader("Retry-After", "0") }
        respond(200, """{"text":"second"}""")
        assertEquals(failure(Server(503)), transcribe(clip()))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `408 is a timeout and is not sent again`() {
        respond(408)
        respond(200, """{"text":"second"}""")
        assertEquals(failure(Timeout), transcribe(clip()))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `307 is rejected and not followed`() {
        respond(307) { addHeader("Location", "/elsewhere") }
        respond(200, """{"text":"second"}""")
        assertEquals(failure(Rejected(307)), transcribe(clip()))
        assertEquals(1, server.requestCount)
    }

    /** Control for the 503 case: OkHttp does re-send a body that is not one-shot. */
    @Test
    fun `OkHttp re-sends a plain body on 503 with Retry-After 0`() {
        respond(503) { addHeader("Retry-After", "0") }
        respond(200)
        val plain = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).build()
        plain.newCall(Request.Builder().url(server.url("/")).post("x".toRequestBody()).build())
            .execute().use { assertEquals(200, it.code) }
        assertEquals(2, server.requestCount)
    }

    // Requests that are never sent

    @Test
    fun `invalid keys return InvalidKey without a request`() {
        val file = clip()
        listOf("", " ", "sk-test fake", " sk-test-fake", "sk-test-fake\n", "sk-t${0xE9.toChar()}st", "sk-test-\u007f")
            .forEach { key -> assertEquals(key, failure(InvalidKey), transcribe(file, key = key)) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a clip over 25,000,000 bytes is too large and not sent`() {
        val file = temp.newFile("large.wav")
        RandomAccessFile(file, "rw").use { it.setLength(TranscriptionClient.MAX_UPLOAD_BYTES + 1) }
        assertEquals(failure(TooLarge), transcribe(file))
        assertEquals(0, server.requestCount)
    }

    // Network, timeout and cancellation

    @Test
    fun `a refused connection is a network failure`() {
        val closed = MockWebServer().apply { start() }
        val url = closed.url("/")
        closed.close()
        val result = runBlocking { TranscriptionClient(url, 5_000).transcribe(clip(), FAKE_KEY) }
        assertEquals(failure(Network), result)
    }

    @Test
    fun `call timeout while waiting for the response is a timeout`() {
        respond(200, """{"text":"late"}""") { onResponseStart(SocketEffect.Stall) }
        val started = System.nanoTime()
        assertEquals(failure(Timeout), transcribe(clip(), client = client(callTimeoutMillis = 300)))
        assertTrue(elapsedMs(started) < 5_000)
    }

    @Test
    fun `call timeout while reading the response body is a timeout`() {
        respond(200, """{"text":"late"}""") { bodyDelay(60, TimeUnit.SECONDS) }
        val started = System.nanoTime()
        assertEquals(failure(Timeout), transcribe(clip(), client = client(callTimeoutMillis = 300)))
        assertTrue(elapsedMs(started) < 5_000)
    }

    @Test
    fun `cancelling while the server stalls ends promptly and maps no error`() {
        respond(200, """{"text":"late"}""") { onResponseStart(SocketEffect.Stall) }
        val file = clip()
        runBlocking {
            val outcome = CompletableDeferred<TranscriptionResult>()
            val job = async(Dispatchers.Default) { outcome.complete(client().transcribe(file, FAKE_KEY)) }
            withTimeout(5_000) {
                while (server.requestCount == 0) delay(10)
            }
            val started = System.nanoTime()
            job.cancel()
            withTimeout(2_000) { job.join() }
            assertTrue(elapsedMs(started) < 2_000)
            assertTrue(job.isCancelled)
            assertFalse(outcome.isCompleted)
        }
    }

    // Key hygiene

    @Test
    fun `no error shows the key, even when the server echoes it`() {
        val file = clip()
        val echo = """{"error":{"message":"Incorrect API key provided: $FAKE_KEY","code":"$FAKE_KEY"}}"""
        listOf(401, 403, 429, 400, 500).forEach { code ->
            respond(code, echo)
            assertNoKey(transcribe(file), FAKE_KEY)
        }
        respond(200, """{"text":null,"echo":"$FAKE_KEY"}""")
        assertNoKey(transcribe(file), FAKE_KEY)

        val badKey = "$FAKE_KEY\n"
        assertNoKey(transcribe(file, key = badKey), FAKE_KEY)
    }

    @Test
    fun `every error type prints without the key`() {
        val errors = listOf(
            InvalidKey, TooLarge, Unauthorized, Forbidden, RateLimited, QuotaExceeded,
            Timeout, Network, Server(500), Rejected(400), Malformed, NoSpeech,
        )
        errors.forEach { assertNoKey(failure(it), FAKE_KEY) }
    }

    private fun assertNoKey(result: TranscriptionResult, key: String) {
        if (result.toString().contains(key) || result.toString().contains("sk-")) {
            fail("Result shows the key: $result")
        }
    }

    private fun elapsedMs(startedNanos: Long) = (System.nanoTime() - startedNanos) / 1_000_000

    private fun errorBody(code: String?, type: String = "requests"): String {
        val codeJson = if (code == null) "null" else "\"$code\""
        return """{"error":{"message":"m","type":"$type","param":null,"code":$codeJson}}"""
    }

    private class FormPart(val name: String?, val filename: String?, val contentType: String?, val bytes: ByteArray)

    private fun formParts(request: RecordedRequest): List<FormPart> {
        val boundary = request.headers["Content-Type"]!!.toMediaType().parameter("boundary")!!
        val parts = mutableListOf<FormPart>()
        MultipartReader(Buffer().write(request.body!!), boundary).use { reader ->
            while (true) {
                val part = reader.nextPart() ?: break
                val disposition = part.headers["Content-Disposition"].orEmpty()
                parts += FormPart(
                    name = Regex("""\bname="([^"]*)"""").find(disposition)?.groupValues?.get(1),
                    filename = Regex("""\bfilename="([^"]*)"""").find(disposition)?.groupValues?.get(1),
                    contentType = part.headers["Content-Type"],
                    bytes = part.body.readByteArray(),
                )
            }
        }
        return parts
    }

    private companion object {
        const val FAKE_KEY = "sk-test-fake"
        val CLIP_BYTES = ByteArray(64) { it.toByte() }
    }
}
