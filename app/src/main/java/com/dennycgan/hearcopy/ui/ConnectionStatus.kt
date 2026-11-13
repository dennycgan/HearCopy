package com.dennycgan.hearcopy.ui

import androidx.annotation.StringRes
import com.dennycgan.hearcopy.R
import com.dennycgan.hearcopy.state.KeyTest

/**
 * The Settings Connection block (ADR-019): the result line under Test
 * transcription and whether the button is enabled. [reason], when set, is
 * the failure text that [line] takes as its argument. [error] shows the
 * line in the error colour.
 */
data class ConnectionStatus(
    @StringRes val line: Int,
    @StringRes val reason: Int? = null,
    val canTest: Boolean,
    val error: Boolean = false,
)

/** From whether a key is stored and the last test; an unreadable result shows until the next reset. */
fun connectionStatus(keyPresent: Boolean, keyTest: KeyTest): ConnectionStatus = when {
    keyTest == KeyTest.Unreadable -> ConnectionStatus(R.string.key_test_unreadable, canTest = keyPresent, error = true)
    !keyPresent -> ConnectionStatus(R.string.key_test_not_configured, canTest = false)
    keyTest == KeyTest.Running -> ConnectionStatus(R.string.key_test_running, canTest = false)
    keyTest == KeyTest.Passed -> ConnectionStatus(R.string.key_test_passed, canTest = true)
    keyTest is KeyTest.Failed ->
        ConnectionStatus(R.string.key_test_failed, reason = failureStatusRes(keyTest.reason), canTest = true, error = true)
    else -> ConnectionStatus(R.string.key_test_not_tested, canTest = true)
}
