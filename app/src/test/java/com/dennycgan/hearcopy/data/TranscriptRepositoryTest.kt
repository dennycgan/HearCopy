package com.dennycgan.hearcopy.data

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A real preferences DataStore in a temporary folder. */
class TranscriptRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    private fun file() = File(folder.root, TranscriptRepository.STORE_PATH)

    /** A fresh DataStore instance on [file], as after a process restart. */
    private fun open(): TranscriptRepository {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        return TranscriptRepository(TranscriptRepository.createStore(file(), scope))
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    @Test
    fun `nothing stored loads as null`() = runBlocking {
        assertNull(open().load())
    }

    @Test
    fun `a saved transcript replaces the previous one and survives a restart`() = runBlocking {
        val first = open()
        assertTrue(first.save("first"))
        assertTrue(first.save("second"))
        scopes.removeAt(0).cancel()

        assertEquals("second", open().load())
    }

    @Test
    fun `a corrupt file loads as null and can be written again`() = runBlocking {
        file().parentFile!!.mkdirs()
        file().writeBytes(byteArrayOf(0x7F, 0x01, 0x02, 0x03))
        val repository = open()
        assertNull(repository.load())
        assertTrue(repository.save("fresh"))
        assertEquals("fresh", repository.load())
    }

    @Test
    fun `it lives in its own file, apart from the API key`() {
        assertEquals("datastore/transcript.preferences_pb", TranscriptRepository.STORE_PATH)
        assertTrue(TranscriptRepository.STORE_PATH != SettingsRepository.STORE_PATH)
    }
}
