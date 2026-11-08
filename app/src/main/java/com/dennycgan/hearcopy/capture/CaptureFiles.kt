package com.dennycgan.hearcopy.capture

import java.io.File

/**
 * Temporary capture audio under `<root>/capture/`, and the debug-only
 * retention of the last valid clips under `<root>/debug/` (Cap 1 design D2).
 * Plain java.io; whether to retain is decided by the caller.
 *
 * [root] is the app's no-backup directory.
 */
class CaptureFiles(root: File, private val retainDebug: Boolean) {

    private val tempDir = File(root, TEMP_DIR)
    private val debugDir = File(root, DEBUG_DIR)

    /**
     * Deletes every file in the temporary directory. Called synchronously at
     * process start, before any recording can exist. Never touches debug/.
     */
    fun clearTemp() {
        tempDir.listFiles()?.forEach { it.delete() }
    }

    /**
     * The temporary file for [attempt]. Unique per attempt within a process,
     * so a late reader of an abandoned attempt can only delete its own clip;
     * earlier processes' files are gone after [clearTemp].
     */
    fun newTempFile(attempt: Int): File {
        tempDir.mkdirs()
        return File(tempDir, "attempt-$attempt.wav")
    }

    /**
     * Takes ownership of a saved clip. With retention on, it becomes
     * `debug/capture-1.wav` and older clips shift down (2 -> 3, 1 -> 2);
     * otherwise it is deleted. Returns false if a file operation failed;
     * the clip is deleted in that case.
     */
    fun keepOrDelete(saved: File): Boolean {
        if (!retainDebug) return saved.delete() || !saved.exists()
        debugDir.mkdirs()
        var ok = true
        for (index in RETAINED downTo 2) {
            val from = debugClip(index - 1)
            if (!from.exists()) continue
            val to = debugClip(index)
            if (to.exists() && !to.delete()) ok = false
            if (!from.renameTo(to)) ok = false
        }
        val newest = debugClip(1)
        if (newest.exists() && !newest.delete()) ok = false
        if (!saved.renameTo(newest)) {
            saved.delete()
            ok = false
        }
        return ok
    }

    /** `debug/capture-[index].wav`; 1 is the newest. */
    fun debugClip(index: Int): File = File(debugDir, "capture-$index.wav")

    companion object {
        const val TEMP_DIR = "capture"
        const val DEBUG_DIR = "debug"
        const val RETAINED = 3
    }
}
