package com.dennycgan.hearcopy.capture

import androidx.annotation.StringRes
import com.dennycgan.hearcopy.R
import com.dennycgan.hearcopy.state.FailureReason

/**
 * The fixed text of a failure notification. The same strings as the main
 * screen's status line; never an HTTP code, server message or exception text.
 */
@StringRes
fun failureTextRes(reason: FailureReason): Int = when (reason) {
    FailureReason.PreparingTimeout -> R.string.status_failed_timeout
    FailureReason.InitFailed -> R.string.status_failed_init
    FailureReason.ReadError -> R.string.status_failed_read
    FailureReason.NoAudio -> R.string.status_failed_no_audio
    FailureReason.NoSpeech -> R.string.status_failed_no_speech
    FailureReason.Unauthorized -> R.string.status_failed_unauthorized
    FailureReason.Forbidden -> R.string.status_failed_forbidden
    FailureReason.RateLimited -> R.string.status_failed_rate_limited
    FailureReason.QuotaExceeded -> R.string.status_failed_quota
    FailureReason.Timeout -> R.string.status_failed_transcription_timeout
    FailureReason.Network -> R.string.status_failed_network
    FailureReason.Server -> R.string.status_failed_server
    FailureReason.Rejected -> R.string.status_failed_rejected
    FailureReason.Malformed -> R.string.status_failed_malformed
    FailureReason.TooLarge -> R.string.status_failed_too_large
    FailureReason.KeyUnavailable -> R.string.status_failed_key_unavailable
}
