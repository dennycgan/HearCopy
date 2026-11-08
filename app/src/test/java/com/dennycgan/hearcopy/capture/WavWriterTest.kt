package com.dennycgan.hearcopy.capture

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WavWriterTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `header matches 16 kHz mono 16-bit and the data length`() {
        val file = File(temp.root, "clip.wav")
        val writer = WavWriter(file)
        writer.write(shortArrayOf(1, -2, 0x1234, Short.MIN_VALUE), 4)
        writer.write(shortArrayOf(Short.MAX_VALUE, 9), 1)
        writer.finish()

        val bytes = file.readBytes()
        assertEquals(44 + 10, bytes.size)
        assertHeader(bytes, dataBytes = 10)
        val data = ByteBuffer.wrap(bytes, 44, 10).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(listOf<Short>(1, -2, 0x1234, Short.MIN_VALUE, Short.MAX_VALUE), List(5) { data.short })
    }

    @Test
    fun `an empty clip still has a valid header`() {
        val file = File(temp.root, "empty.wav")
        WavWriter(file).finish()

        val bytes = file.readBytes()
        assertEquals(44, bytes.size)
        assertHeader(bytes, dataBytes = 0)
    }

    @Test
    fun `abort deletes the file`() {
        val file = File(temp.root, "aborted.wav")
        val writer = WavWriter(file)
        writer.write(ShortArray(100), 100)
        writer.abort()

        assertFalse(file.exists())
    }

    companion object {
        fun assertHeader(bytes: ByteArray, dataBytes: Int) {
            val header = ByteBuffer.wrap(bytes, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
            fun tag(): String = String(ByteArray(4).also { header.get(it) }, Charsets.US_ASCII)

            assertEquals("RIFF", tag())
            assertEquals(36 + dataBytes, header.int)
            assertEquals("WAVE", tag())
            assertEquals("fmt ", tag())
            assertEquals(16, header.int)
            assertEquals(1.toShort(), header.short) // PCM
            assertEquals(1.toShort(), header.short) // mono
            assertEquals(16_000, header.int)
            assertEquals(32_000, header.int) // byte rate
            assertEquals(2.toShort(), header.short) // block align
            assertEquals(16.toShort(), header.short)
            assertEquals("data", tag())
            assertEquals(dataBytes, header.int)
        }
    }
}
