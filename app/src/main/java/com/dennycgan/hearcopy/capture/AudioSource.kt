package com.dennycgan.hearcopy.capture

/**
 * A source of PCM 16-bit, 16 kHz, mono audio. Pure Kotlin so that
 * [CaptureSession] can be tested on the JVM with a fake.
 *
 * Threading: [start], [read] and [release] are called on the reader thread.
 * [stop] may be called from another thread while [read] is blocked and must
 * make that [read] return.
 */
interface AudioSource {

    /** Starts delivering audio. Throws if the source cannot start. */
    fun start()

    /**
     * Blocking read of up to [size] samples into [buffer] at [offset].
     * Returns the number of samples read (> 0), 0 if none were available,
     * or a negative error code.
     */
    fun read(buffer: ShortArray, offset: Int, size: Int): Int

    /** Stops delivering audio and unblocks a pending [read]. */
    fun stop()

    /**
     * Frees the source. Called exactly once, by the reader, after its read
     * loop has exited; must be safe whether or not [start] or [stop] ran.
     */
    fun release()
}
