package com.skeksik.current.core.database

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The web's secret store logic (`LocalStorageSecretStore`), over a storage that can be told to fail like a blocked `localStorage`. */
class FallbackSecretStoreTest {
    private class FakeStorage(var available: Boolean = true) : FallibleStorage {
        val values = mutableMapOf<String, String>()
        var writesFail = false
        var removesFail = false

        override fun probe(): Boolean = available

        override fun get(key: String): String? = if (available) values[key] else null

        override fun put(key: String, value: String): Boolean {
            if (!available || writesFail) return false
            values[key] = value
            return true
        }

        override fun remove(key: String): Boolean {
            if (!available || removesFail) return false
            values.remove(key)
            return true
        }
    }

    @Test
    fun workingStorageKeepsSecrets() = runBlocking {
        val backend = FakeStorage()
        val store = FallbackSecretStore(backend, SecretStorage.Browser)
        store.put("token", "a")
        assertEquals(SecretStorage.Browser, store.storage.value)
        assertEquals("a", backend.values["token"])
        assertEquals("a", store.get("token"))
        store.remove("token")
        assertTrue(backend.values.isEmpty())
        assertNull(store.get("token"))
    }

    @Test
    fun blockedStorageSaysMemoryAndStillHoldsSecretsForTheRun() = runBlocking {
        val store = FallbackSecretStore(FakeStorage(available = false), SecretStorage.Browser)
        assertEquals(SecretStorage.Memory, store.storage.value)
        store.put("token", "a")
        assertEquals("a", store.get("token"))
        store.remove("token")
        assertNull(store.get("token"))
    }

    @Test
    fun aFailedWriteTurnsToMemoryWithoutLosingWhatIsKept() = runBlocking {
        val backend = FakeStorage()
        val store = FallbackSecretStore(backend, SecretStorage.Browser)
        store.put("first", "a")
        backend.writesFail = true
        store.put("second", "b")
        assertEquals(SecretStorage.Memory, store.storage.value)
        assertEquals("a", store.get("first"))
        assertEquals("b", store.get("second"))
        // Once in memory, a write that would work again stays in memory: the state the user was told holds for the run.
        backend.writesFail = false
        store.put("third", "c")
        assertEquals(SecretStorage.Memory, store.storage.value)
        assertNull(backend.values["third"])
        assertEquals("c", store.get("third"))
    }

    @Test
    fun aRemovalThatStorageRefusesStillHidesTheSecret() = runBlocking {
        val backend = FakeStorage()
        val store = FallbackSecretStore(backend, SecretStorage.Browser)
        store.put("token", "a")
        backend.removesFail = true
        store.remove("token")
        assertNull(store.get("token"))
    }
}
