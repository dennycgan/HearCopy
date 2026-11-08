package com.dennycgan.hearcopy.capture

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.SystemClock
import android.util.Log

/**
 * Builds the [AudioSource] for one capture attempt from the session's
 * projection. Declared here so that AppContainer can hold the factory
 * without naming the projection type itself (Cap 1 design D5).
 */
typealias AudioSourceFactory = (MediaProjection) -> AudioSource

/**
 * Captures audio that other apps play (USAGE_MEDIA, USAGE_GAME,
 * USAGE_UNKNOWN) and delivers PCM 16-bit, 16 kHz, mono. One instance per
 * attempt. The record runs at 48 kHz, the rate the platform capture path
 * delivers cleanly, and [Decimator] converts it to 16 kHz (ADR-004). If the
 * record does not run at 48 kHz, [start] fails.
 */
class PlaybackCaptureSource(private val projection: MediaProjection) : AudioSource {

    private val lock = Any()
    private var record: AudioRecord? = null

    // Reader-thread state.
    private val decimator = Decimator()
    private var input = ShortArray(Decimator.FACTOR * CaptureSession.CHUNK_SAMPLES)

    // RECORD_AUDIO is checked with checkSelfPermission by CaptureService at
    // tap time, right before this attempt is created.
    @SuppressLint("MissingPermission")
    override fun start() {
        val startedAt = SystemClock.elapsedRealtime()
        val minBuffer = AudioRecord.getMinBufferSize(CAPTURE_RATE, CHANNEL_MASK, ENCODING)
        val bufferBytes = maxOf(minBuffer, MIN_BUFFER_BYTES)
        Log.d(TAG, "minBufferSize=$minBuffer bufferBytes=$bufferBytes")

        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(ENCODING)
            .setSampleRate(CAPTURE_RATE)
            .setChannelMask(CHANNEL_MASK)
            .build()
        val built = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes)
            .setAudioPlaybackCaptureConfig(config)
            .build()
        // Held before any check, so release() frees it whatever fails next.
        synchronized(lock) { record = built }

        val state = built.state
        val rate = built.sampleRate
        Log.d(TAG, "AudioRecord state=$state sampleRate=$rate")
        check(state == AudioRecord.STATE_INITIALIZED) { "record not initialised: state=$state" }
        if (rate != CAPTURE_RATE) {
            Log.w(TAG, "sampleRate=$rate, expected $CAPTURE_RATE")
            error("unexpected sample rate $rate")
        }

        built.startRecording()
        Log.d(TAG, "startRecording returned ${SystemClock.elapsedRealtime() - startedAt} ms after start")
    }

    /**
     * Reads at most [size] 16 kHz samples. Asks the record for at most the
     * 48 kHz input that yields [size] outputs; 0 and error codes from the
     * record are returned unchanged. Reader thread only.
     */
    override fun read(buffer: ShortArray, offset: Int, size: Int): Int {
        val current = synchronized(lock) { record } ?: return AudioRecord.ERROR_INVALID_OPERATION
        while (true) {
            val want = decimator.maxInputFor(size)
            if (input.size < want) input = ShortArray(want)
            val n = current.read(input, 0, want, AudioRecord.READ_BLOCKING)
            if (n <= 0) return n
            val produced = decimator.process(input, 0, n, buffer, offset)
            // Zero outputs only when n < 3 - pending; read again for the rest.
            if (produced > 0) return produced
        }
    }

    override fun stop() {
        val current = synchronized(lock) { record } ?: return
        try {
            current.stop()
        } catch (e: IllegalStateException) {
            // Not recording (start failed before startRecording); nothing to unblock.
            Log.d(TAG, "stop skipped: ${e.javaClass.simpleName}")
        }
    }

    override fun release() {
        val current = synchronized(lock) { record.also { record = null } } ?: return
        current.release()
    }

    private companion object {
        const val TAG = "HearCopyCapture"
        const val CAPTURE_RATE = Decimator.INPUT_RATE
        const val CHANNEL_MASK = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** 4 x 100 ms of 48 kHz mono 16-bit audio. */
        const val MIN_BUFFER_BYTES = 4 * 9_600
    }
}
