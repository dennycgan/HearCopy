package com.dennycgan.hearcopy.capture

/**
 * A source of PCM 16-bit, 16 kHz, mono audio. Pure Kotlin so that
 * [CaptureLoop] and [CaptureSession] can be tested on the JVM with a fake.
 *
 * One source runs for a whole capture session (ADR-005): it is started once
 * and read continuously; [skip] while no clip is recorded, [read] while one
 * is.
 *
 * Threading: [start], [read], [skip] and [release] are called on the reader
 * thread. [stop] may be called from another thread while [read] or [skip]
 * is blocked and must make that call return.
 */
interface AudioSource {

    /** Starts delivering audio. Throws if the source cannot start. */
    fun start()

    /**
     * Blocking read of up to [size] samples into [buffer] at [offset].
     * Returns the number of samples read (> 0), 0 if none were available,
     * or a negative error code. The first read after a [skip] starts a
     * fresh conversion: no skipped audio contributes to its output.
     */
    fun read(buffer: ShortArray, offset: Int, size: Int): Int

    /**
     * Blocking read of one chunk at the source's native rate, discarded at
     * once without conversion. Returns how many native samples were read
     * (> 0), 0 if none were available, or a negative error code.
     */
    fun skip(): Int

    /** Stops delivering audio and unblocks a pending [read] or [skip]. */
    fun stop()

    /**
     * Frees the source. Called exactly once, by the reader, after its last
     * read; must be safe whether or not [start] or [stop] ran.
     */
    fun release()
}
