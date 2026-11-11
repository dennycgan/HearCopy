package com.dennycgan.hearcopy.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The save path trims, then applies the shared key rule (also tested in TranscriptionClientTest). */
class ApiKeyInputTest {

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("sk-test-fake", normaliseApiKey("  sk-test-fake \n"))
        assertEquals("sk-test-fake", normaliseApiKey("\tsk-test-fake"))
    }

    @Test
    fun `a valid key is kept as typed`() {
        assertEquals("sk-test-fake", normaliseApiKey("sk-test-fake"))
    }

    @Test
    fun `an empty or blank key is rejected`() {
        assertNull(normaliseApiKey(""))
        assertNull(normaliseApiKey("   "))
    }

    @Test
    fun `inner whitespace and characters outside visible ASCII are rejected`() {
        assertNull(normaliseApiKey("sk-test fake"))
        assertNull(normaliseApiKey("sk-test-fäke"))
        assertNull(normaliseApiKey("sk-test-fake\u0000"))
    }
}
