package com.skeksik.current.core.database

import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** An OS credential store. Calls block and throw [SecretBackendException] when the store refuses or is unavailable. */
internal interface SecretBackend {
    /** Which store this is, for the UI to name it. */
    val storage: SecretStorage

    fun get(key: String): String?

    fun put(key: String, value: String)

    fun remove(key: String)
}

internal class SecretBackendException(message: String) : Exception(message)

/**
 * Secrets on the desktop (ADR 0017): the [keyring] (Windows Credential Manager, or the Secret Service on Linux) when there is one.
 * Without one, memory, unless the user chose a file only they can read ([keepInFile]), which is remembered in [local]. If the keyring
 * fails at run time (a locked keyring the user dismisses), values stay in memory for the rest of the run; removals still try the keyring.
 */
internal class DesktopSecretStore(
    private val keyring: SecretBackend?,
    private val local: KeyValueStore,
    private val secretsFile: Path,
) : SecretStore {
    private val lock = Mutex()
    private val memory = mutableMapOf<String, String>()
    private var file: JsonFileKeyValueStore? =
        if (keyring == null && local.get(FILE_CHOSEN) == "true") JsonFileKeyValueStore(secretsFile, ownerOnly = true) else null

    private val _storage = MutableStateFlow(
        when {
            keyring != null -> keyring.storage
            file != null -> SecretStorage.File
            else -> SecretStorage.Memory
        },
    )
    override val storage: StateFlow<SecretStorage> = _storage.asStateFlow()

    override val fileOffered: Boolean get() = keyring == null && file == null

    private val usingKeyring: Boolean get() = keyring != null && _storage.value == keyring.storage

    override suspend fun keepInFile() {
        io {
            if (!fileOffered) return@io
            local.put(FILE_CHOSEN, "true")
            val chosen = JsonFileKeyValueStore(secretsFile, ownerOnly = true)
            memory.forEach { (key, value) -> chosen.put(key, value) }
            memory.clear()
            file = chosen
            _storage.value = SecretStorage.File
        }
    }

    override suspend fun get(key: String): String? = io {
        memory[key] ?: file?.get(key) ?: if (usingKeyring) keyringOrNull { it.get(key) } else null
    }

    override suspend fun put(key: String, value: String) {
        io {
            val stored = when {
                file != null -> true.also { file!!.put(key, value) }
                usingKeyring -> keyringOrNull { it.put(key, value) } != null
                else -> false
            }
            if (stored) memory.remove(key) else memory[key] = value
        }
    }

    override suspend fun remove(key: String) {
        io {
            memory.remove(key)
            file?.remove(key)
            when {
                usingKeyring -> keyringOrNull { it.remove(key) }
                // Still tried after a failure switched the run to memory: a token left in the keyring would sign the user back in
                // at the next start. Best effort, so a second failure changes nothing.
                keyring != null -> try {
                    keyring.remove(key)
                } catch (e: SecretBackendException) {
                    // Nothing more to do this run.
                }
            }
        }
    }

    /** Runs [action] on the keyring; on failure switches to memory for the rest of the run and returns null. */
    private fun <T> keyringOrNull(action: (SecretBackend) -> T): T? = try {
        action(keyring!!)
    } catch (e: SecretBackendException) {
        _storage.value = SecretStorage.Memory
        null
    }

    private suspend fun <T> io(block: suspend () -> T): T = lock.withLock { withContext(Dispatchers.IO) { block() } }

    companion object {
        /** In the machine-local store: the user chose a file because there was no keyring. */
        internal const val FILE_CHOSEN = "secrets.fileChosen"

        fun create(local: KeyValueStore): DesktopSecretStore {
            val keyring = if (AppFolders.isWindows) WindowsCredentials.openOrNull() else LibSecret.openOrNull()
            return DesktopSecretStore(keyring, local, AppFolders.local().resolve("secrets.json"))
        }
    }
}
