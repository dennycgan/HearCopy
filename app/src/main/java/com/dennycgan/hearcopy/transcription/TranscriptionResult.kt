package com.dennycgan.hearcopy.transcription

/**
 * Outcome of one upload. Nothing in it carries the API key, an exception
 * message or a response body.
 */
sealed interface TranscriptionResult {

    /** The transcript. [toString] shows only its length, so the text cannot reach a log. */
    class Success(val text: String) : TranscriptionResult {
        override fun equals(other: Any?): Boolean = other is Success && other.text == text
        override fun hashCode(): Int = text.hashCode()
        override fun toString(): String = "Success(length=${text.length})"
    }

    data class Failure(val error: TranscriptionError) : TranscriptionResult
}

/** Why an upload produced no transcript. */
sealed interface TranscriptionError {

    /** The key is empty or has a character outside visible ASCII; nothing was sent. */
    data object InvalidKey : TranscriptionError

    /** The clip is larger than the API file limit; nothing was sent. */
    data object TooLarge : TranscriptionError

    /** HTTP 401. */
    data object Unauthorized : TranscriptionError

    /** HTTP 403. */
    data object Forbidden : TranscriptionError

    /** HTTP 429 without a billing signal. */
    data object RateLimited : TranscriptionError

    /** HTTP 429 with a billing signal (quota, credit or spend limit). */
    data object QuotaExceeded : TranscriptionError

    /** The call timeout fired, or HTTP 408. */
    data object Timeout : TranscriptionError

    /** The connection failed, including a connect timeout. */
    data object Network : TranscriptionError

    /** HTTP 5xx. */
    data class Server(val code: Int) : TranscriptionError

    /** Any other non-2xx status, including 3xx (redirects are not followed). */
    data class Rejected(val code: Int) : TranscriptionError

    /** A 2xx response without a string `text` field. */
    data object Malformed : TranscriptionError

    /** A 2xx response whose `text` is blank. */
    data object NoSpeech : TranscriptionError
}
