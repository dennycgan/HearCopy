package com.dennycgan.hearcopy.capture

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecimatorTest {

    private val inputRate = Decimator.INPUT_RATE

    /** The filter's DC gain in dB (-3 dB of headroom); responses are checked relative to it. */
    private val headroomDb = 20 * log10(Decimator.HEADROOM)

    private fun sine(hz: Double, amplitude: Double, count: Int) =
        ShortArray(count) { n -> (amplitude * sin(2 * PI * hz * n / inputRate)).roundToInt().toShort() }

    /** Decimates [input] in one call. */
    private fun oneShot(input: ShortArray): ShortArray {
        val out = ShortArray(input.size / Decimator.FACTOR + 1)
        val n = Decimator().process(input, 0, input.size, out, 0)
        return out.copyOf(n)
    }

    /** RMS of [samples], skipping the filter's start-up. */
    private fun steadyRms(samples: ShortArray): Double {
        val from = Decimator.TAPS // well past the 175-input start-up, in output samples
        val tail = samples.copyOfRange(from, samples.size)
        return sqrt(tail.sumOf { it.toDouble() * it } / tail.size)
    }

    private fun gainDb(hz: Double): Double {
        val amplitude = 20_000.0
        val out = oneShot(sine(hz, amplitude, inputRate))
        return 20 * log10(steadyRms(out) / (amplitude / sqrt(2.0)))
    }

    /** Magnitude response of the taps at [hz], in dB. */
    private fun responseDb(taps: DoubleArray, hz: Double): Double {
        var re = 0.0
        var im = 0.0
        for (n in taps.indices) {
            val w = 2 * PI * hz * n / inputRate
            re += taps[n] * cos(w)
            im -= taps[n] * sin(w)
        }
        return 10 * log10(re * re + im * im)
    }

    @Test
    fun `capture rate is exactly the output rate times the factor`() {
        assertEquals(WavWriter.SAMPLE_RATE * Decimator.FACTOR, Decimator.INPUT_RATE)
    }

    @Test
    fun `DC gain is HEADROOM`() {
        val out = oneShot(ShortArray(4_800) { 10_000 })
        val expected = (10_000 * Decimator.HEADROOM).roundToInt()
        out.copyOfRange(Decimator.TAPS, out.size).forEach { assertTrue("got $it, expected $expected", abs(it - expected) <= 1) }
    }

    @Test
    fun `1 kHz passes essentially unattenuated relative to HEADROOM`() {
        val db = gainDb(1_000.0) - headroomDb
        assertTrue("1 kHz gain $db dB", abs(db) <= 0.1)
    }

    @Test
    fun `9 kHz and 10 kHz are attenuated by at least 60 dB relative to HEADROOM`() {
        for (hz in listOf(9_000.0, 10_000.0)) {
            val db = gainDb(hz) - headroomDb
            assertTrue("$hz Hz gain $db dB", db <= -60.0)
        }
    }

    @Test
    fun `taps meet the spec relative to HEADROOM - passband to 7 kHz within 0,1 dB, at least 60 dB from 8 kHz`() {
        val taps = Decimator.lowPassTaps()
        assertEquals(Decimator.TAPS, taps.size)
        var hz = 0.0
        while (hz <= 7_000.0) {
            val db = responseDb(taps, hz) - headroomDb
            assertTrue("passband $hz Hz: $db dB", abs(db) <= 0.1)
            hz += 25.0
        }
        hz = 8_000.0
        while (hz <= inputRate / 2.0) {
            val db = responseDb(taps, hz) - headroomDb
            assertTrue("stopband $hz Hz: $db dB", db <= -60.0)
            hz += 25.0
        }
    }

    @Test
    fun `chunked output equals one-shot output for chunk sizes that are not multiples of 3`() {
        val noise = Random(42)
        val input = ShortArray(20_000) { noise.nextInt(-20_000, 20_000).toShort() }
        val expected = oneShot(input)
        val random = Random(1)
        val plans = listOf(listOf(1), listOf(2), listOf(4), listOf(5), listOf(7), listOf(1_601)) +
            listOf(List(200) { random.nextInt(1, 500) })
        for (plan in plans) {
            val decimator = Decimator()
            val out = ShortArray(expected.size + 1)
            var inPos = 0
            var outPos = 0
            var step = 0
            while (inPos < input.size) {
                val size = minOf(plan[step % plan.size], input.size - inPos)
                outPos += decimator.process(input, inPos, size, out, outPos)
                inPos += size
                step++
            }
            assertArrayEquals("plan ${plan.take(3)}", expected, out.copyOf(outPos))
        }
    }

    @Test
    fun `output count is floor of pending plus input over 3, and maxInputFor never exceeds the request`() {
        val decimator = Decimator()
        val out = ShortArray(10_000)
        var pending = 0
        val random = Random(3)
        repeat(500) {
            val request = random.nextInt(1, 50)
            val max = decimator.maxInputFor(request)
            assertEquals(Decimator.FACTOR * request - pending, max)
            val n = random.nextInt(1, max + 1)
            val produced = decimator.process(ShortArray(n), 0, n, out, 0)
            assertEquals((pending + n) / Decimator.FACTOR, produced)
            assertTrue(produced <= request)
            pending = (pending + n) % Decimator.FACTOR
        }
    }

    /** Direct convolution of [taps] over [input]; output j has input 3j + 2 as its newest sample. */
    private fun reference(input: ShortArray, taps: DoubleArray, j: Int): Double {
        val newest = Decimator.FACTOR * j + Decimator.FACTOR - 1
        var acc = 0.0
        for (k in taps.indices) {
            val i = newest - (taps.size - 1) + k
            if (i >= 0) acc += taps[k] * input[i]
        }
        return acc
    }

    @Test
    fun `reset forgets history and phase, so the output equals a new decimator's`() {
        val noise = Random(7)
        val earlier = ShortArray(1_001) { noise.nextInt(-20_000, 20_000).toShort() }
        val input = ShortArray(4_800) { noise.nextInt(-20_000, 20_000).toShort() }
        val used = Decimator()
        used.process(earlier, 0, earlier.size, ShortArray(earlier.size), 0)

        used.reset()
        val out = ShortArray(input.size / Decimator.FACTOR)
        val n = used.process(input, 0, input.size, out, 0)

        assertArrayEquals(oneShot(input), out.copyOf(n))
    }

    @Test
    fun `full-scale square wave never reaches the clamp`() {
        val input = ShortArray(9_600) { n -> if ((n / 24) % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }
        val taps = Decimator.lowPassTaps()
        val out = oneShot(input)

        var peak = 0.0
        for (j in out.indices) {
            val exact = reference(input, taps, j)
            peak = maxOf(peak, abs(exact))
            assertEquals("output $j", exact.roundToInt().toShort(), out[j])
        }
        // The overshoot exists (above HEADROOM x full scale) but stays below the clamp.
        assertTrue("peak $peak shows no overshoot", peak > Decimator.HEADROOM * Short.MAX_VALUE)
        assertTrue("peak $peak reaches the clamp", peak < Short.MAX_VALUE)
    }

    @Test
    fun `a contrived input above full scale clamps instead of wrapping`() {
        // Each input matches the sign of its tap, so one output sums |taps| x full scale.
        val taps = Decimator.lowPassTaps()
        val j = 60
        val first = Decimator.FACTOR * j + Decimator.FACTOR - 1 - (taps.size - 1)
        val input = ShortArray(first + taps.size + Decimator.FACTOR)
        for (k in taps.indices) input[first + k] = if (taps[k] >= 0) Short.MAX_VALUE else Short.MIN_VALUE
        val out = oneShot(input)

        assertTrue(reference(input, taps, j) > Short.MAX_VALUE)
        assertEquals(Short.MAX_VALUE, out[j])
    }
}
