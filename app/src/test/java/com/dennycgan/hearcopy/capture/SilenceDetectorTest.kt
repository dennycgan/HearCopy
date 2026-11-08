package com.dennycgan.hearcopy.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceDetectorTest {

    private val frame = SilenceDetector.FRAME_SAMPLES

    private fun SilenceDetector.feed(amplitude: Int, count: Int) =
        accept(ShortArray(count) { amplitude.toShort() }, count)

    @Test
    fun `threshold is -50 dBFS of a full-scale square wave`() {
        assertEquals(320, frame)
        assertEquals(103.6, SilenceDetector.THRESHOLD_RMS, 0.05)
    }

    @Test
    fun `all zeros is silent`() {
        val detector = SilenceDetector()
        detector.feed(0, frame * 10)

        assertTrue(detector.isSilent)
        assertEquals(Double.NEGATIVE_INFINITY, detector.loudestFrameDbfs, 0.0)
    }

    @Test
    fun `a frame just above the threshold is not silent`() {
        val detector = SilenceDetector()
        detector.feed(0, frame * 3)
        detector.feed(104, frame)

        assertFalse(detector.isSilent)
        assertEquals(3, detector.firstLoudFrameIndex)
    }

    @Test
    fun `frames just below the threshold are silent`() {
        val detector = SilenceDetector()
        detector.feed(103, frame * 5)

        assertTrue(detector.isSilent)
        assertNull(detector.firstLoudFrameIndex)
    }

    @Test
    fun `a frame split across reads is measured as one frame`() {
        val detector = SilenceDetector()
        // One loud frame delivered as 100 + 220 samples; alone, neither part
        // forms a frame.
        detector.feed(0, frame)
        detector.feed(1000, 100)
        assertTrue(detector.isSilent)
        detector.feed(1000, 220)

        assertEquals(1, detector.firstLoudFrameIndex)
    }

    @Test
    fun `a trailing partial frame is ignored`() {
        val detector = SilenceDetector()
        detector.feed(0, frame)
        detector.feed(30000, frame - 1)

        assertTrue(detector.isSilent)
    }

    @Test
    fun `loudest frame level is reported in dBFS`() {
        val detector = SilenceDetector()
        detector.feed(200, frame)
        detector.feed(1000, frame)
        detector.feed(300, frame)

        // 20 * log10(1000 / 32768)
        assertEquals(-30.31, detector.loudestFrameDbfs, 0.01)
    }
}
