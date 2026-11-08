package com.dennycgan.hearcopy.capture

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streams PCM 16-bit, 16 kHz, mono samples to a WAV file. Writes a 44-byte
 * header with zero sizes first; [finish] patches the RIFF and data sizes.
 * Not thread-safe: used only by the reader thread.
 */
class WavWriter(private val file: File) {

    private val out = BufferedOutputStream(FileOutputStream(file))
    private var bytes = ByteArray(0)
    private var dataBytes = 0
    private var closed = false

    init {
        out.write(header(dataBytes = 0))
    }

    fun write(samples: ShortArray, count: Int) {
        val size = count * BYTES_PER_SAMPLE
        if (bytes.size < size) bytes = ByteArray(size)
        for (i in 0 until count) {
            val sample = samples[i].toInt()
            bytes[2 * i] = sample.toByte()
            bytes[2 * i + 1] = (sample shr 8).toByte()
        }
        out.write(bytes, 0, size)
        dataBytes += size
    }

    /** Closes the stream and patches the header sizes to the data written. */
    fun finish() {
        closed = true
        out.close()
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(RIFF_SIZE_OFFSET)
            raf.write(le32(RIFF_SIZE_BASE + dataBytes))
            raf.seek(DATA_SIZE_OFFSET)
            raf.write(le32(dataBytes))
        }
    }

    /** Closes the stream, ignoring errors, and deletes the file. */
    fun abort() {
        if (!closed) {
            closed = true
            try {
                out.close()
            } catch (_: Exception) {
                // The file is deleted next; a close error changes nothing.
            }
        }
        file.delete()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNELS = 1
        const val BITS_PER_SAMPLE = 16
        const val HEADER_BYTES = 44

        private const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
        private const val RIFF_SIZE_OFFSET = 4L
        private const val DATA_SIZE_OFFSET = 40L
        private const val RIFF_SIZE_BASE = HEADER_BYTES - 8

        private fun header(dataBytes: Int): ByteArray =
            ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(RIFF_SIZE_BASE + dataBytes)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16) // fmt chunk size
                putShort(1) // PCM
                putShort(CHANNELS.toShort())
                putInt(SAMPLE_RATE)
                putInt(SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE) // byte rate
                putShort((CHANNELS * BYTES_PER_SAMPLE).toShort()) // block align
                putShort(BITS_PER_SAMPLE.toShort())
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(dataBytes)
            }.array()

        private fun le32(value: Int): ByteArray =
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }
}
