package com.skeksik.current.core.database

import kotlin.io.path.createTempFile
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking

/**
 * The real OS stores. Windows Credential Manager runs wherever the tests run on Windows (entries named `Current-test-…`, removed again).
 * The Secret Service tests run only when `CURRENT_TEST_SECRET_SERVICE` names their setup: `1` (an unlocked keyring; CI's Linux job starts
 * GNOME Keyring in a D-Bus session), `locked` or `absent`.
 */
class KeyringTest {
    private val key = "test/" + Random.nextLong().toULong().toString(16)

    private fun roundTrip(keyring: SecretBackend) {
        try {
            assertNull(keyring.get(key))
            keyring.put(key, "first")
            keyring.put(key, "tökén ✓")
            assertEquals("tökén ✓", keyring.get(key))
        } finally {
            keyring.remove(key)
        }
        assertNull(keyring.get(key))
        keyring.remove(key)
    }

    @Test
    fun windowsCredentialManager() {
        if (!AppFolders.isWindows) return
        val keyring = assertNotNull(WindowsCredentials.openOrNull(prefix = "Current-test-"))
        roundTrip(keyring)
        assertFailsWith<IllegalArgumentException> { keyring.put(key, "x".repeat(2561)) }
    }

    @Test
    fun linuxSecretService() {
        if (System.getenv("CURRENT_TEST_SECRET_SERVICE") != "1") return
        roundTrip(assertNotNull(LibSecret.openOrNull(), "no Secret Service answered"))
    }

    /**
     * With `CURRENT_TEST_SECRET_SERVICE=locked` (a session bus whose keyring is neither unlocked nor able to prompt), a write fails
     * within the timeout instead of hanging, and the store keeps the value in memory.
     */
    @Test
    fun aKeyringThatCannotAnswerFallsBackToMemory() = runBlocking {
        if (System.getenv("CURRENT_TEST_SECRET_SERVICE") != "locked") return@runBlocking
        val keyring = assertNotNull(LibSecret.openOrNull(timeout = 10.seconds))
        val started = System.nanoTime()
        assertFailsWith<SecretBackendException> { keyring.put(key, "value") }
        assertTrue(System.nanoTime() - started < 15_000_000_000L, "gave up within the timeout")

        val store = DesktopSecretStore(LibSecret.openOrNull(timeout = 10.seconds), InMemoryKeyValueStore(), createTempFile())
        store.put(key, "value")
        assertEquals(SecretStorage.Memory, store.storage.value)
        assertEquals("value", store.get(key))
    }

    /** With `CURRENT_TEST_SECRET_SERVICE=absent` (libsecret installed, no D-Bus session), the store must fall back instead of failing. */
    @Test
    fun noSecretServiceMeansNoKeyring() {
        if (System.getenv("CURRENT_TEST_SECRET_SERVICE") != "absent") return
        assertNull(LibSecret.openOrNull())
        assertEquals(SecretStorage.Memory, DesktopSecretStore.create(InMemoryKeyValueStore()).storage.value)
    }
}
