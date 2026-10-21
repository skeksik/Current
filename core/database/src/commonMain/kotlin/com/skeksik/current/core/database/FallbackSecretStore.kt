package com.skeksik.current.core.database

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Storage whose calls can fail, such as `localStorage` when the browser blocks it or it is full: writes say whether they stuck. */
internal interface FallibleStorage {
    /** Whether storage works at all, checked once by writing a key and removing it again. */
    fun probe(): Boolean

    fun get(key: String): String?

    fun put(key: String, value: String): Boolean

    fun remove(key: String): Boolean
}

/**
 * Secrets in [backend] while it works, with [storage] reporting [kept]. When [backend] is unavailable at start, or a write fails later,
 * [storage] turns to [SecretStorage.Memory] for the rest of the run: new values stay in memory and what is already kept can still be
 * read, so nothing is lost until the app closes.
 */
internal class FallbackSecretStore(private val backend: FallibleStorage, kept: SecretStorage) : SecretStore {
    private val usable = backend.probe()

    /** What [backend] does not hold. A key mapped to null was removed while [backend] would not remove it. */
    private val memory = mutableMapOf<String, String?>()

    private val _storage = MutableStateFlow(if (usable) kept else SecretStorage.Memory)
    override val storage: StateFlow<SecretStorage> = _storage.asStateFlow()

    override suspend fun get(key: String): String? = when {
        key in memory -> memory[key]
        usable -> backend.get(key)
        else -> null
    }

    override suspend fun put(key: String, value: String) {
        if (_storage.value != SecretStorage.Memory && backend.put(key, value)) {
            memory.remove(key)
        } else {
            _storage.value = SecretStorage.Memory
            memory[key] = value
        }
    }

    override suspend fun remove(key: String) {
        // Still tried after a failed write: a token left in storage would sign the user back in at the next start.
        if (usable && backend.remove(key)) memory.remove(key) else memory[key] = null
    }
}
