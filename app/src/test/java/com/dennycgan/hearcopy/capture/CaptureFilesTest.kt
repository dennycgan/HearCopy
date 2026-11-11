package com.dennycgan.hearcopy.capture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CaptureFilesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun files(retainDebug: Boolean = true) = CaptureFiles(temp.root, retainDebug)

    /** Saves clips whose content is their sequence number, oldest first. */
    private fun CaptureFiles.saveClips(count: Int) {
        for (n in 1..count) {
            val clip = newTempFile(attempt = n).apply { writeText("clip $n") }
            assertTrue(keepOrDelete(clip))
            assertFalse(clip.exists())
        }
    }

    private fun CaptureFiles.retained(): List<String?> =
        (1..CaptureFiles.RETAINED).map { debugClip(it).takeIf(File::exists)?.readText() }

    @Test
    fun `one clip becomes capture-1`() {
        val files = files()
        files.saveClips(1)
        assertEquals(listOf("clip 1", null, null), files.retained())
    }

    @Test
    fun `two clips keep the newest in capture-1`() {
        val files = files()
        files.saveClips(2)
        assertEquals(listOf("clip 2", "clip 1", null), files.retained())
    }

    @Test
    fun `three clips fill capture-1 to capture-3 newest first`() {
        val files = files()
        files.saveClips(3)
        assertEquals(listOf("clip 3", "clip 2", "clip 1"), files.retained())
    }

    @Test
    fun `a fourth clip drops the oldest`() {
        val files = files()
        files.saveClips(4)
        assertEquals(listOf("clip 4", "clip 3", "clip 2"), files.retained())
        assertEquals(CaptureFiles.RETAINED, File(temp.root, CaptureFiles.DEBUG_DIR).listFiles()!!.size)
    }

    @Test
    fun `clearTemp empties the temporary directory and never touches debug`() {
        val files = files()
        files.saveClips(2)
        val stale = listOf(files.newTempFile(7), files.newTempFile(8)).onEach { it.writeText("stale") }

        files.clearTemp()

        stale.forEach { assertFalse(it.exists()) }
        assertEquals(listOf("clip 2", "clip 1", null), files.retained())
    }

    @Test
    fun `clearTemp without a temporary directory does nothing`() {
        files().clearTemp()
        assertFalse(File(temp.root, CaptureFiles.TEMP_DIR).exists())
    }

    @Test
    fun `release mode deletes the saved file and keeps nothing`() {
        val files = files(retainDebug = false)
        val clip = files.newTempFile(1).apply { writeText("clip") }

        assertTrue(files.keepOrDelete(clip))

        assertFalse(clip.exists())
        assertFalse(File(temp.root, CaptureFiles.DEBUG_DIR).exists())
    }

    @Test
    fun `discard deletes a clip even with retention on and never touches debug`() {
        val files = files(retainDebug = true)
        files.keepOrDelete(files.newTempFile(1).apply { writeText("kept") })
        val clip = files.newTempFile(2).apply { writeText("cancelled") }

        assertTrue(files.discard(clip))

        assertFalse(clip.exists())
        assertTrue(files.debugClip(1).exists())
        assertFalse(files.debugClip(2).exists())
    }

    @Test
    fun `temp file names are unique per attempt`() {
        val files = files()
        assertTrue(files.newTempFile(1) != files.newTempFile(2))
    }
}
