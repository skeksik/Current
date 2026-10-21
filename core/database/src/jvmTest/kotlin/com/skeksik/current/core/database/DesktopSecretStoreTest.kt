package com.skeksik.current.core.database

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopSecretStoreTest {
    private val folder: Path = Files.createTempDirectory("current-secrets-test")
    private val secretsFile: Path = folder.resolve("state").resolve("secrets.json")
    private val local = InMemoryKeyValueStore()

    @AfterTest
    fun deleteFolder() {
        folder.toFile().deleteRecursively()
    }

    private class FakeKeyring(
        var failing: Boolean = false,
        override val storage: SecretStorage = SecretStorage.CredentialManager,
    ) : SecretBackend {
        val values = mutableMapOf<String, String>()

        override fun get(key: String): String? = if (failing) throw SecretBackendException("locked") else values[key]

        override fun put(key: String, value: String) {
            if (failing) throw SecretBackendException("locked")
            values[key] = value
        }

        override fun remove(key: String) {
            if (failing) throw SecretBackendException("locked")
            values.remove(key)
        }
    }

    @Test
    fun aKeyringHoldsTheSecrets() = runBlocking {
        val keyring = FakeKeyring()
        val store = DesktopSecretStore(keyring, local, secretsFile)
        assertEquals(SecretStorage.CredentialManager, store.storage.value)
        assertFalse(store.fileOffered)
        store.put("jellyfin/a/token", "t1")
        assertEquals(mapOf("jellyfin/a/token" to "t1"), keyring.values)
        assertEquals("t1", store.get("jellyfin/a/token"))
        store.remove("jellyfin/a/token")
        assertNull(store.get("jellyfin/a/token"))
        assertFalse(Files.exists(secretsFile))
    }

    @Test
    fun theStoreNamesTheKeyringItUses() = runBlocking {
        val keyring = FakeKeyring(storage = SecretStorage.SecretService)
        val store = DesktopSecretStore(keyring, local, secretsFile)
        assertEquals(SecretStorage.SecretService, store.storage.value)
        store.put("jellyfin/a/token", "t1")
        assertEquals(mapOf("jellyfin/a/token" to "t1"), keyring.values)
    }

    @Test
    fun aFailingKeyringFallsBackToMemoryForTheRun() = runBlocking {
        val keyring = FakeKeyring(failing = true)
        val store = DesktopSecretStore(keyring, local, secretsFile)
        store.put("jellyfin/a/token", "t1")
        assertEquals(SecretStorage.Memory, store.storage.value)
        assertEquals("t1", store.get("jellyfin/a/token"))
        keyring.failing = false
        store.put("jellyfin/b/token", "t2")
        assertTrue(keyring.values.isEmpty())
    }

    @Test
    fun removingStillClearsTheKeyringAfterAFailure() = runBlocking {
        val keyring = FakeKeyring()
        val store = DesktopSecretStore(keyring, local, secretsFile)
        store.put("jellyfin/a/token", "t1")

        // The keyring asks to be unlocked and the user dismisses it: the run switches to memory and the saved token is not seen.
        keyring.failing = true
        assertNull(store.get("jellyfin/a/token"))
        assertEquals(SecretStorage.Memory, store.storage.value)

        // Signing out must not leave the token behind for the next start.
        keyring.failing = false
        store.remove("jellyfin/a/token")
        assertTrue(keyring.values.isEmpty())
        assertEquals(SecretStorage.Memory, store.storage.value)
    }

    @Test
    fun aRemovalThatFailsAgainIsIgnored() = runBlocking {
        val keyring = FakeKeyring(failing = true)
        val store = DesktopSecretStore(keyring, local, secretsFile)
        store.put("jellyfin/a/token", "t1")
        store.remove("jellyfin/a/token")
        assertNull(store.get("jellyfin/a/token"))
        assertEquals(SecretStorage.Memory, store.storage.value)
    }

    @Test
    fun aValueTooLongForCredentialManagerIsAStoreFailure() {
        assertEquals(2560, WindowsCredentials.blobOf("x".repeat(2560)).size)
        assertFailsWith<SecretBackendException> { WindowsCredentials.blobOf("x".repeat(2561)) }
    }

    @Test
    fun withoutAKeyringSecretsStayInMemoryUntilTheUserChoosesAFile() = runBlocking {
        val store = DesktopSecretStore(null, local, secretsFile)
        assertEquals(SecretStorage.Memory, store.storage.value)
        assertTrue(store.fileOffered)
        store.put("jellyfin/a/token", "t1")
        assertFalse(Files.exists(secretsFile))

        store.keepInFile()
        assertEquals(SecretStorage.File, store.storage.value)
        assertFalse(store.fileOffered)
        assertTrue(Files.exists(secretsFile))

        val afterRestart = DesktopSecretStore(null, local, secretsFile)
        assertEquals(SecretStorage.File, afterRestart.storage.value)
        assertEquals("t1", afterRestart.get("jellyfin/a/token"))
    }

    @Test
    fun theSecretsFileIsReadableByTheOwnerOnly() = runBlocking {
        if ("posix" !in secretsFile.fileSystem.supportedFileAttributeViews()) return@runBlocking
        DesktopSecretStore(null, local, secretsFile).apply {
            keepInFile()
            put("jellyfin/a/token", "t1")
        }
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(secretsFile)))
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(secretsFile.parent)))
    }
}
