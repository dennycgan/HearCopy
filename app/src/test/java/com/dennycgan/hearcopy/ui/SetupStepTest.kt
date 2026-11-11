package com.dennycgan.hearcopy.ui

import com.dennycgan.hearcopy.state.Setup
import org.junit.Assert.assertEquals
import org.junit.Test

class SetupStepTest {

    private val all = Setup(overlayGranted = true, recordAudioGranted = true, projectionActive = true, apiKeyPresent = true)

    @Test
    fun `nothing is asked until the key storage has been read`() {
        assertEquals(SetupStep.Checking, nextStep(Setup()))
        assertEquals(SetupStep.Checking, nextStep(all.copy(apiKeyPresent = null)))
    }

    @Test
    fun `the key comes before every permission`() {
        assertEquals(SetupStep.ApiKey, nextStep(Setup(apiKeyPresent = false)))
    }

    @Test
    fun `a deleted key returns to the key step even with a session`() {
        assertEquals(SetupStep.ApiKey, nextStep(all.copy(apiKeyPresent = false)))
    }

    @Test
    fun `with a key the permission steps follow in order`() {
        assertEquals(SetupStep.Overlay, nextStep(Setup(apiKeyPresent = true)))
        assertEquals(SetupStep.Microphone, nextStep(Setup(overlayGranted = true, apiKeyPresent = true)))
        assertEquals(SetupStep.Consent, nextStep(all.copy(projectionActive = false)))
        assertEquals(SetupStep.Done, nextStep(all))
    }
}
