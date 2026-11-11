package com.dennycgan.hearcopy.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import java.io.File
import java.io.IOException
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A real preferences DataStore in a temporary folder; a software key in place of the Keystore. */
class SettingsRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val keys = FakeKeys()
    private lateinit var file: File
    private lateinit var store: WriteSwitch
    private lateinit var repository: SettingsRepository

    private val fakeKey = "sk-test-fake"

    @Before
    fun setUp() {
        file = File(folder.root, SettingsRepository.STORE_PATH)
    }

    private fun open() {
        store = WriteSwitch(SettingsRepository.createStore(file, scope))
        repository = SettingsRepository(store, keys)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    // Save and presence

    @Test
    fun `nothing stored is absent`() = runBlocking {
        open()
        assertEquals(ApiKeyStatus.Absent, repository.apiKeyStatus())
        assertEquals(ApiKeyRead.Absent, repository.readApiKey())
    }

    @Test
    fun `a saved key is present with its last four characters and reads back`() = runBlocking {
        open()
        assertEquals(ApiKeySave.Saved("fake"), repository.saveApiKey(fakeKey))
        assertEquals(ApiKeyStatus.Present("fake"), repository.apiKeyStatus())
        assertEquals(fakeKey, readKey())
        assertEquals(1, keys.created)
    }

    @Test
    fun `the store file holds no plaintext key`() = runBlocking {
        open()
        repository.saveApiKey(fakeKey)
        val bytes = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse(bytes.contains("sk-test"))
    }

    @Test
    fun `a replaced key reuses the Keystore key`() = runBlocking {
        open()
        repository.saveApiKey(fakeKey)
        assertEquals(ApiKeySave.Saved("ke-b"), repository.saveApiKey("sk-test-fake-b"))
        assertEquals("sk-test-fake-b", readKey())
        assertEquals(1, keys.created)
    }

    @Test
    fun `a Keystore failure on save writes nothing`() = runBlocking {
        open()
        keys.createError = ProviderException("fake")
        assertEquals(ApiKeySave.Failed, repository.saveApiKey(fakeKey))
        assertStoreEmpty()
    }

    @Test
    fun `a store failure on save reports failed`() = runBlocking {
        open()
        store.failWrites = true
        assertEquals(ApiKeySave.Failed, repository.saveApiKey(fakeKey))
        store.failWrites = false
        assertStoreEmpty()
    }

    @Test
    fun `the read result never prints the key`() = runBlocking {
        open()
        repository.saveApiKey(fakeKey)
        assertFalse(repository.readApiKey().toString().contains("sk-test"))
    }

    // Presence check (addition a)

    @Test
    fun `an absent Keystore key deletes blob and hint`() = runBlocking {
        open()
        repository.saveApiKey(fakeKey)
        keys.key = null
        assertEquals(ApiKeyStatus.Absent, repository.apiKeyStatus())
        assertStoreEmpty()
    }

    @Test
    fun `a Keystore error at the presence check keeps the key as present and deletes nothing`() = runBlocking {
        open()
        repository.saveApiKey(fakeKey)
        val blob = storedBlob()
        keys.hasKeyError = KeyStoreException("fake")
        assertEquals(ApiKeyStatus.Present("fake"), repository.apiKeyStatus())
        keys.hasKeyError = null
        assertEquals(0, keys.deleted)
        assertArrayEquals(blob, storedBlob())
        assertEquals(fakeKey, readKey())
    }

    @Test
    fun `a malformed blob is deleted`() = runBlocking {
        open()
        repository.saveApiKey(fakeKey)
        store.edit { it[SettingsRepository.BLOB] = byteArrayOf(2, 0, 0) }
        assertEquals(ApiKeyStatus.Absent, repository.apiKeyStatus())
        assertStoreEmpty()
    }

    @Test
    fun `a blob without its hint is deleted`() = runBlocking {
        open()
        repository.saveApiKey(fakeKey)
        store.edit { it.remove(SettingsRepository.HINT) }
        assertEquals(ApiKeyStatus.Absent, repository.apiKeyStatus())
        assertStoreEmpty()
    }

    @Test
    fun `a corrupt store file reads as absent`() = runBlocking {
        file.parentFile!!.mkdirs()
        file.writeBytes(byteArrayOf(0x7F, 0x01, 0x02, 0x03))
        open()
        assertEquals(ApiKeyStatus.Absent, repository.apiKeyStatus())
    }

    // Delete (addition b)

    @Test
    fun `delete removes the Keystore key, the blob and the hint`() = runBlocking {
        open()
        repository.saveApiKey(fakeKey)
        assertEquals(ApiKeyDelete.Deleted, repository.deleteApiKey())
        assertNull(keys.key)
        assertStoreEmpty()
        assertEquals(ApiKeyStatus.Absent, repository.apiKeyStatus())
    }

    @Test
    fun `a failed Keystore delete changes nothing`() = runBlocking {
        open()
        repository.saveApiKey(fakeKey)
        val blob = storedBlob()
        keys.deleteError = KeyStoreException("fake")
        assertEquals(ApiKeyDelete.Failed, repository.deleteApiKey())
        keys.deleteError = null
        assertNotNull(keys.key)
        assertArrayEquals(blob, storedBlob())
        assertEquals(ApiKeyStatus.Present("fake"), repository.apiKeyStatus())
        assertEquals(fakeKey, readKey())
    }

    @Test
    fun `a failed store clear after the Keystore delete still counts as deleted`() = runBlocking {
        open()
        repository.saveApiKey(fakeKey)
        store.failWrites = true
        assertEquals(ApiKeyDelete.Deleted, repository.deleteApiKey())
        assertNull(keys.key)
        assertNotNull(storedBlob())
        // The next presence check removes the leftover blob.
        store.failWrites = false
        assertEquals(ApiKeyStatus.Absent, repository.apiKeyStatus())
        assertStoreEmpty()
    }

    // Decrypt before an upload

    @Test
    fun `a tag mismatch is unreadable and deletes blob, hint and key`() = runBlocking {
        open()
        repository.saveApiKey(fakeKey)
        keys.key = softwareKey()
        assertEquals(ApiKeyRead.Unreadable, repository.readApiKey())
        assertNull(keys.key)
        assertStoreEmpty()
    }

    @Test
    fun `an absent Keystore key at decrypt is unreadable`() = runBlocking {
        open()
        repository.saveApiKey(fakeKey)
        keys.key = null
        assertEquals(ApiKeyRead.Unreadable, repository.readApiKey())
        assertStoreEmpty()
    }

    @Test
    fun `an unrecoverable Keystore key is unreadable`() = runBlocking {
        open()
        repository.saveApiKey(fakeKey)
        keys.keyError = UnrecoverableKeyException("fake")
        assertEquals(ApiKeyRead.Unreadable, repository.readApiKey())
        assertStoreEmpty()
    }

    @Test
    fun `a malformed blob at decrypt is unreadable`() = runBlocking {
        open()
        repository.saveApiKey(fakeKey)
        store.edit { it[SettingsRepository.BLOB] = byteArrayOf(1, 2, 3) }
        assertEquals(ApiKeyRead.Unreadable, repository.readApiKey())
        assertStoreEmpty()
    }

    @Test
    fun `a transient Keystore error is unavailable and deletes nothing`() = runBlocking {
        open()
        repository.saveApiKey(fakeKey)
        val blob = storedBlob()
        for (error in listOf(ProviderException("fake"), KeyStoreException("fake"))) {
            keys.keyError = error
            assertEquals("$error", ApiKeyRead.Unavailable, repository.readApiKey())
        }
        keys.keyError = null
        assertEquals(0, keys.deleted)
        assertArrayEquals(blob, storedBlob())
        assertEquals(fakeKey, readKey())
    }

    private suspend fun readKey(): String = (repository.readApiKey() as ApiKeyRead.Key).value

    private suspend fun storedBlob(): ByteArray? = store.data.first()[SettingsRepository.BLOB]

    private suspend fun assertStoreEmpty() {
        assertTrue(store.data.first().asMap().isEmpty())
    }

    private fun softwareKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    /** The real store, with writes that can be made to fail as a disk error would. */
    private class WriteSwitch(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
        var failWrites = false

        override val data: Flow<Preferences> get() = delegate.data

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            if (failWrites) throw IOException("fake write failure")
            return delegate.updateData(transform)
        }

        /** Test setup writes that bypass [failWrites]. */
        suspend fun edit(block: (MutablePreferences) -> Unit) {
            delegate.edit { block(it) }
        }
    }

    /** A software AES-256 key with switchable failures. */
    private inner class FakeKeys : SecretKeySource {
        var key: SecretKey? = null
        var created = 0
        var deleted = 0
        var hasKeyError: Exception? = null
        var keyError: Exception? = null
        var createError: Exception? = null
        var deleteError: Exception? = null

        override fun hasKey(): Boolean {
            hasKeyError?.let { throw it }
            return key != null
        }

        override fun key(): SecretKey? {
            keyError?.let { throw it }
            return key
        }

        override fun createKey(): SecretKey {
            createError?.let { throw it }
            created++
            return softwareKey().also { key = it }
        }

        override fun deleteKey() {
            deleteError?.let { throw it }
            if (key != null) deleted++
            key = null
        }
    }
}
