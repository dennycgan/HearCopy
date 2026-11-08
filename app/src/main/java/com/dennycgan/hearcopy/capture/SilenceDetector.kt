package com.dennycgan.hearcopy.capture

import kotlin.math.log10
import kotlin.math.pow

/**
 * Streaming silence check over 20 ms frames. A clip is silent if no frame's
 * RMS exceeds -50 dBFS, referenced to a full-scale square wave. A trailing
 * partial frame is ignored. Not thread-safe: used only by the reader thread.
 */
class SilenceDetector {

    private var samplesInFrame = 0
    private var sumSquares = 0.0
    private var frames = 0
    private var maxMeanSquare = 0.0

    /** Index of the first frame above the threshold, or null if none. */
    var firstLoudFrameIndex: Int? = null
        private set

    val isSilent: Boolean get() = firstLoudFrameIndex == null

    /** Level of the loudest complete frame; negative infinity if none. */
    val loudestFrameDbfs: Double
        get() = if (frames == 0) Double.NEGATIVE_INFINITY else 10 * log10(maxMeanSquare / FULL_SCALE_SQUARED)

    fun accept(samples: ShortArray, count: Int) {
        for (i in 0 until count) {
            val sample = samples[i].toDouble()
            sumSquares += sample * sample
            samplesInFrame++
            if (samplesInFrame == FRAME_SAMPLES) closeFrame()
        }
    }

    private fun closeFrame() {
        val meanSquare = sumSquares / FRAME_SAMPLES
        if (meanSquare > maxMeanSquare) maxMeanSquare = meanSquare
        if (firstLoudFrameIndex == null && meanSquare > THRESHOLD_MEAN_SQUARE) firstLoudFrameIndex = frames
        frames++
        samplesInFrame = 0
        sumSquares = 0.0
    }

    companion object {
        const val FRAME_MS = 20
        const val FRAME_SAMPLES = WavWriter.SAMPLE_RATE * FRAME_MS / 1000
        const val THRESHOLD_DBFS = -50.0

        private const val FULL_SCALE = 32768.0
        private const val FULL_SCALE_SQUARED = FULL_SCALE * FULL_SCALE

        /** RMS threshold in sample units, about 103.6. */
        val THRESHOLD_RMS: Double = FULL_SCALE * 10.0.pow(THRESHOLD_DBFS / 20)

        private val THRESHOLD_MEAN_SQUARE = THRESHOLD_RMS * THRESHOLD_RMS
    }
}
