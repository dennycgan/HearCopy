package com.dennycgan.hearcopy.ui

import com.dennycgan.hearcopy.R
import com.dennycgan.hearcopy.state.CaptureState
import com.dennycgan.hearcopy.state.CaptureStatus
import com.dennycgan.hearcopy.state.FailureReason
import com.dennycgan.hearcopy.state.Setup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class StatusCardTest {

    private val ready = Setup(overlayGranted = true, recordAudioGranted = true, projectionActive = true, apiKeyPresent = true)

    private fun card(
        setup: Setup = ready,
        status: CaptureStatus = CaptureStatus.Ready,
        awaitingSession: Boolean = false,
        declined: Boolean = false,
        microphoneBlocked: Boolean = false,
    ) = statusCard(CaptureState(setup = setup, status = status), awaitingSession, declined, microphoneBlocked)

    @Test
    fun `nothing but a spinner until the key storage has been read`() {
        val checking = card(setup = ready.copy(apiKeyPresent = null), awaitingSession = true, declined = true)
        assertEquals(StatusCard(CardIcon.Spinner, R.string.status_checking_title), checking)
    }

    @Test
    fun `a starting session shows Starting and cannot be tapped`() {
        val starting = card(setup = ready.copy(projectionActive = false), awaitingSession = true, declined = true)
        assertEquals(StatusCard(CardIcon.Spinner, R.string.status_starting_title, R.string.status_starting), starting)
    }

    @Test
    fun `finishing comes before any setup step and cannot be tapped`() {
        val lost = ready.copy(projectionActive = false)
        val expected = StatusCard(CardIcon.Spinner, R.string.status_finishing_title, R.string.status_finishing)
        assertEquals(expected, card(setup = lost, status = CaptureStatus.Processing))
        assertEquals(expected, card(setup = lost.copy(overlayGranted = false), status = CaptureStatus.Processing, declined = true))
        assertEquals(expected, card(setup = lost, status = CaptureStatus.Completed, declined = true))
    }

    @Test
    fun `the key step opens Settings and never asks to try again`() {
        val expected = StatusCard(
            CardIcon.Alert,
            R.string.status_setup_title,
            R.string.setup_api_key_text,
            tap = CardTap.OpenSettings,
        )
        assertEquals(expected, card(setup = Setup(apiKeyPresent = false)))
        assertEquals(expected, card(setup = ready.copy(apiKeyPresent = false), declined = true))
    }

    @Test
    fun `each permission step names itself and retries the step`() {
        val overlay = Setup(apiKeyPresent = true)
        val microphone = overlay.copy(overlayGranted = true)
        val consent = ready.copy(projectionActive = false)
        assertEquals(setup(R.string.setup_overlay_text, CardTap.RetryStep), card(setup = overlay))
        assertEquals(setup(R.string.setup_microphone_text, CardTap.RetryStep), card(setup = microphone))
        assertEquals(setup(R.string.setup_capture_text, CardTap.RetryStep), card(setup = consent))
    }

    @Test
    fun `a declined step asks to try again`() {
        val consent = ready.copy(projectionActive = false)
        assertEquals(
            setup(R.string.setup_capture_text, CardTap.RetryStep, tryAgain = true),
            card(setup = consent, declined = true),
        )
    }

    @Test
    fun `a blocked microphone opens app info with its own text`() {
        val microphone = Setup(apiKeyPresent = true, overlayGranted = true)
        val expected = setup(R.string.setup_microphone_blocked_text, CardTap.OpenAppInfo)
        assertEquals(expected, card(setup = microphone, microphoneBlocked = true))
        assertEquals(expected, card(setup = microphone, microphoneBlocked = true, declined = true))
    }

    @Test
    fun `a blocked microphone flag is ignored at any other step`() {
        assertEquals(
            setup(R.string.setup_overlay_text, CardTap.RetryStep),
            card(setup = Setup(apiKeyPresent = true), microphoneBlocked = true),
        )
        assertEquals(R.string.status_ready_title, card(microphoneBlocked = true).title)
    }

    @Test
    fun `Preparing shows as Ready`() {
        val expected = StatusCard(CardIcon.Check, R.string.status_ready_title, R.string.status_ready)
        assertEquals(expected, card(status = CaptureStatus.Ready))
        assertEquals(expected, card(status = CaptureStatus.Preparing))
    }

    @Test
    fun `Recording carries its start for the timer`() {
        assertEquals(
            StatusCard(CardIcon.Stop, R.string.status_recording_title, R.string.status_recording, recordingSinceMs = 4_200L),
            card(status = CaptureStatus.Recording(startedAtMs = 4_200L)),
        )
    }

    @Test
    fun `Processing and Completed`() {
        assertEquals(
            StatusCard(CardIcon.Spinner, R.string.status_processing_title, R.string.status_processing),
            card(status = CaptureStatus.Processing),
        )
        assertEquals(
            StatusCard(CardIcon.Check, R.string.status_completed_title, R.string.status_completed),
            card(status = CaptureStatus.Completed),
        )
    }

    @Test
    fun `Failed shows the reason text and only key failures open Settings`() {
        val keyFailures = setOf(
            FailureReason.Unauthorized,
            FailureReason.Forbidden,
            FailureReason.QuotaExceeded,
            FailureReason.KeyUnavailable,
        )
        FailureReason.entries.forEach { reason ->
            val tap = if (reason in keyFailures) CardTap.OpenSettings else CardTap.None
            assertEquals(
                "$reason",
                StatusCard(CardIcon.Alert, R.string.status_failed_title, failureStatusRes(reason), tap = tap),
                card(status = CaptureStatus.Failed(reason)),
            )
        }
    }

    @Test
    fun `Ready, Recording, Processing and Completed cannot be tapped`() {
        listOf(
            card(status = CaptureStatus.Ready),
            card(status = CaptureStatus.Recording(0)),
            card(status = CaptureStatus.Processing),
            card(status = CaptureStatus.Completed),
        ).forEach {
            assertEquals(CardTap.None, it.tap)
            assertFalse(it.tryAgain)
        }
        assertNull(card(setup = Setup()).description)
    }

    @Test
    fun `the timer counts whole seconds and waits for the next one`() {
        assertEquals(0L, recordedSeconds(-5))
        assertEquals(0L, recordedSeconds(999))
        assertEquals(12L, recordedSeconds(12_400))
        assertEquals(600L, msToNextSecond(12_400))
        assertEquals(1000L, msToNextSecond(12_000))
        assertEquals(1000L, msToNextSecond(-5))
    }

    private fun setup(text: Int, tap: CardTap, tryAgain: Boolean = false) =
        StatusCard(CardIcon.Alert, R.string.status_setup_title, text, tryAgain = tryAgain, tap = tap)
}
