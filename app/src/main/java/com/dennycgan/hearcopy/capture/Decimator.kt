package com.dennycgan.hearcopy.capture

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Converts 48 kHz PCM 16-bit to 16 kHz: a linear-phase low-pass FIR, then
 * every [FACTOR]-th sample is kept (ADR-004). Streaming: filter history and
 * phase carry across calls, so chunked input gives exactly the same output
 * as one call. Only the kept outputs are computed.
 *
 * Filter: Kaiser-windowed sinc, [TAPS] taps, passband to 7 kHz, at least
 * 60 dB attenuation from 8 kHz, normalised to a DC gain of exactly 1 and
 * then scaled by [HEADROOM]. The headroom keeps the filter's overshoot on
 * loud audio (about 4% above full scale on real captures) away from the
 * 16-bit clamp; the output is 3 dB quieter than the source. The clamp stays
 * as a safety net: the sum of |taps| is about 2.2, so a contrived input can
 * still reach it.
 *
 * Not thread-safe: used only by the reader thread.
 */
class Decimator {

    private val taps = lowPassTaps()

    /** The last [TAPS] input samples, oldest first, as a ring. */
    private val history = DoubleArray(TAPS)
    private var head = 0

    /** Inputs consumed since the last output: 0, 1 or 2. */
    private var pending = 0

    /** Forgets all history and phase, as if newly built. Used when a clip starts (ADR-005). */
    fun reset() {
        history.fill(0.0)
        head = 0
        pending = 0
    }

    /** Max input samples to read so that [process] emits at most [outputCount] samples. */
    fun maxInputFor(outputCount: Int): Int = FACTOR * outputCount - pending

    /**
     * Consumes [inCount] samples from [input] at [inOffset] and writes the
     * outputs to [out] at [outOffset]. Returns how many were written:
     * floor((pending + inCount) / FACTOR).
     */
    fun process(input: ShortArray, inOffset: Int, inCount: Int, out: ShortArray, outOffset: Int): Int {
        var written = 0
        for (i in inOffset until inOffset + inCount) {
            history[head] = input[i].toDouble()
            head = (head + 1) % TAPS
            pending++
            if (pending == FACTOR) {
                pending = 0
                out[outOffset + written] = filtered()
                written++
            }
        }
        return written
    }

    /** One filter output from the current history; the filter is symmetric. */
    private fun filtered(): Short {
        var acc = 0.0
        var index = head // oldest sample
        for (k in 0 until TAPS) {
            acc += taps[k] * history[index]
            index++
            if (index == TAPS) index = 0
        }
        return acc.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    companion object {
        const val FACTOR = 3
        const val TAPS = 175
        const val INPUT_RATE = 48_000

        /** Design cutoff: middle of the 7-8 kHz transition band. */
        private const val CUTOFF_HZ = 7_500.0

        /** Kaiser beta for 60 dB: 0.1102 * (60 - 8.7). */
        private const val KAISER_BETA = 5.653

        /** Headroom against filter overshoot: -3 dB, 10^(-3/20). The DC gain of the taps. */
        val HEADROOM: Double = 10.0.pow(-3.0 / 20.0)

        /** The taps, oldest-sample weight first (Kaiser windowed sinc, DC gain [HEADROOM]). */
        fun lowPassTaps(): DoubleArray {
            val middle = (TAPS - 1) / 2.0
            val normalisedCutoff = 2.0 * CUTOFF_HZ / INPUT_RATE
            val taps = DoubleArray(TAPS) { n ->
                val x = n - middle
                val sinc = if (x == 0.0) normalisedCutoff else sin(PI * normalisedCutoff * x) / (PI * x)
                val ratio = x / middle
                sinc * besselI0(KAISER_BETA * sqrt(1.0 - ratio * ratio)) / besselI0(KAISER_BETA)
            }
            val scale = HEADROOM / taps.sum()
            for (n in taps.indices) taps[n] *= scale
            return taps
        }

        /** Modified Bessel function of the first kind, order 0 (power series). */
        private fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val half = x / 2.0
            var k = 1
            while (term > sum * 1e-12) {
                term *= (half / k) * (half / k)
                sum += term
                k++
            }
            return sum
        }
    }
}
