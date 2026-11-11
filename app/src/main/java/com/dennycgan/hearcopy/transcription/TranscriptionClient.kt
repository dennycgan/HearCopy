package com.dennycgan.hearcopy.transcription

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONException
import org.json.JSONObject

/**
 * Uploads one clip to OpenAI gpt-transcribe and maps the outcome to a
 * [TranscriptionResult] (ADR-012).
 *
 * Each clip is sent at most once: the body is one-shot, connection retries
 * and redirects are off, and nothing is retried here. The key is passed per
 * call and never stored. Errors carry no exception message, response body
 * or key.
 */
class TranscriptionClient internal constructor(
    baseUrl: HttpUrl,
    callTimeoutMillis: Long,
) {

    /** The production client: the OpenAI endpoint and the default call timeout. */
    constructor() : this(DEFAULT_BASE_URL.toHttpUrl(), DEFAULT_CALL_TIMEOUT_MS)

    internal val endpoint: HttpUrl = baseUrl.newBuilder().encodedPath(PATH).build()

    internal val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .build()

    /**
     * Uploads [file] with [apiKey]. Cancelling the coroutine cancels the
     * call and rethrows; it is never mapped to an error.
     */
    suspend fun transcribe(file: File, apiKey: String): TranscriptionResult {
        if (!isValidApiKey(apiKey)) return failure(TranscriptionError.InvalidKey)
        if (file.length() > MAX_UPLOAD_BYTES) return failure(TranscriptionError.TooLarge)
        val request = try {
            buildRequest(file, apiKey)
        } catch (e: IllegalArgumentException) {
            // Unreachable while isValidApiKey runs first: every key it accepts
            // passes OkHttp's header value check. Kept as a backstop; the
            // exception message is dropped because it can describe the key.
            return failure(TranscriptionError.InvalidKey)
        }
        val call = httpClient.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resume(failure(afterIoFailure(call)))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use(::interpret)
                    } catch (e: IOException) {
                        failure(afterIoFailure(call))
                    }
                    continuation.resume(result)
                }
            })
        }
    }

    private fun buildRequest(file: File, apiKey: String): Request {
        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", MODEL)
            .addFormDataPart("languages[]", LANGUAGE)
            .addFormDataPart("file", file.name, file.asRequestBody(WAV))
            .build()
        return Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .post(OneShotBody(form))
            .build()
    }

    /** Reads the body only for 2xx and 429; the caller closes [response]. */
    private fun interpret(response: Response): TranscriptionResult {
        val code = response.code
        return when {
            code in 200..299 -> parseTranscript(response.bodyText())
            code == 401 -> failure(TranscriptionError.Unauthorized)
            code == 403 -> failure(TranscriptionError.Forbidden)
            code == 408 -> failure(TranscriptionError.Timeout)
            code == 429 -> failure(
                if (isBillingLimit(response.bodyText())) {
                    TranscriptionError.QuotaExceeded
                } else {
                    TranscriptionError.RateLimited
                },
            )
            code in 500..599 -> failure(TranscriptionError.Server(code))
            else -> failure(TranscriptionError.Rejected(code))
        }
    }

    /**
     * Only this client's cancellation handler and the call timeout cancel a
     * call. After a coroutine cancellation the resume is ignored, so a
     * cancelled call that still resumes means the call timeout fired.
     */
    private fun afterIoFailure(call: Call): TranscriptionError =
        if (call.isCanceled()) TranscriptionError.Timeout else TranscriptionError.Network

    private fun parseTranscript(body: String): TranscriptionResult {
        val text = parseObject(body)?.stringOrNull("text")
            ?: return failure(TranscriptionError.Malformed)
        return if (text.isBlank()) failure(TranscriptionError.NoSpeech) else TranscriptionResult.Success(text)
    }

    private fun isBillingLimit(body: String): Boolean {
        val error = parseObject(body)?.optJSONObject("error") ?: return false
        val type = error.stringOrNull("type")
        val code = error.stringOrNull("code")
        return type == "insufficient_quota" ||
            code in BILLING_CODES ||
            code?.endsWith(SPEND_LIMIT_SUFFIX) == true
    }

    private fun parseObject(body: String): JSONObject? =
        try {
            JSONObject(body)
        } catch (e: JSONException) {
            null
        }

    /**
     * A string field, or null when missing, JSON null or not a string. Android's
     * org.json turns a JSON null into the string "null" in optString and
     * getString, so neither is used.
     */
    private fun JSONObject.stringOrNull(name: String): String? =
        if (isNull(name)) null else opt(name) as? String

    /** UTF-8 regardless of the declared charset, so a bad charset cannot throw. */
    private fun Response.bodyText(): String = String(body.bytes(), Charsets.UTF_8)

    private fun failure(error: TranscriptionError) = TranscriptionResult.Failure(error)

    /** Stops every OkHttp follow-up and recovery that would send the clip again. */
    private class OneShotBody(private val delegate: RequestBody) : RequestBody() {
        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
    }

    internal companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/"
        const val DEFAULT_CALL_TIMEOUT_MS = 120_000L
        const val CONNECT_TIMEOUT_MS = 15_000L

        /** The API's "up to 25 MB", read as decimal megabytes. */
        const val MAX_UPLOAD_BYTES = 25_000_000L

        private const val PATH = "/v1/audio/transcriptions"
        private const val MODEL = "gpt-transcribe"
        private const val LANGUAGE = "en"
        private val WAV = "audio/wav".toMediaType()

        private val BILLING_CODES = setOf(
            "credit_balance_exhausted",
            "organization_usage_limit_exceeded",
        )
        private const val SPEND_LIMIT_SUFFIX = "_spend_limit_exceeded"

        /** The key save rule: non-empty, every character visible ASCII (0x21-0x7E). No trim. */
        fun isValidApiKey(key: String): Boolean =
            key.isNotEmpty() && key.all { it.code in 0x21..0x7E }
    }
}
