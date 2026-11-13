package com.dennycgan.hearcopy.data

import androidx.datastore.preferences.core.edit
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
class TouchPositionRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    private fun file() = File(folder.root, TouchPositionRepository.STORE_PATH)

    private fun scope() = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }

    /** A fresh DataStore instance on [file], as after a process restart. */
    private fun open(): TouchPositionRepository =
        TouchPositionRepository(TouchPositionRepository.createStore(file(), scope()))

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    @Test
    fun `nothing stored loads as null`() = runBlocking {
        assertNull(open().load())
    }

    @Test
    fun `a saved position replaces the previous one and survives a restart`() = runBlocking {
        val first = open()
        assertTrue(first.save(10, 20))
        assertTrue(first.save(-3, 1_234))
        scopes.removeAt(0).cancel()

        assertEquals(-3 to 1_234, open().load())
    }

    @Test
    fun `half a position loads as null`() = runBlocking {
        val scope = scope()
        TouchPositionRepository.createStore(file(), scope).edit { it[TouchPositionRepository.X] = 10 }
        scopes.remove(scope)
        scope.cancel()

        assertNull(open().load())
    }

    @Test
    fun `a corrupt file loads as null and can be written again`() = runBlocking {
        file().parentFile!!.mkdirs()
        file().writeBytes(byteArrayOf(0x7F, 0x01, 0x02, 0x03))
        val repository = open()
        assertNull(repository.load())
        assertTrue(repository.save(5, 6))
        assertEquals(5 to 6, repository.load())
    }

    @Test
    fun `it lives in its own file, apart from the API key and the transcript`() {
        assertEquals("datastore/touch.preferences_pb", TouchPositionRepository.STORE_PATH)
        assertTrue(TouchPositionRepository.STORE_PATH != SettingsRepository.STORE_PATH)
        assertTrue(TouchPositionRepository.STORE_PATH != TranscriptRepository.STORE_PATH)
    }
}
