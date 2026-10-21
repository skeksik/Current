package com.skeksik.current.core.database

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/** Where [SecretStore] keeps secrets right now, so the UI can say what "stay signed in" means here (ADR 0017). */
enum class SecretStorage {
    /** Windows Credential Manager, the OS credential store on Windows. */
    CredentialManager,

    /** The Secret Service, the OS credential store on Linux (GNOME Keyring, KWallet or another provider; which one is not known). */
    SecretService,

    /** The browser's `localStorage`: kept across restarts, readable by any script on the page's origin. */
    Browser,

    /** A file only this user can read, which the user chose because no keyring was available. */
    File,

    /** Nowhere: lost when the app quits, so the user signs in again after a restart. */
    Memory,
}

/**
 * Tokens and keys (ADR 0017). Not for settings: those go to [KeyValueStore]. Calls may block on the OS (a keyring that asks to be
 * unlocked), so they suspend. When the platform's store fails, values stay in memory and [storage] turns to [SecretStorage.Memory].
 */
interface SecretStore {
    val storage: StateFlow<SecretStorage>

    /**
     * True when secrets only live in memory but a file only this user can read is possible (Linux without a keyring). The UI may offer
     * it; [keepInFile] switches over and moves what memory holds.
     */
    val fileOffered: Boolean get() = false

    suspend fun keepInFile() = Unit

    suspend fun get(key: String): String?

    suspend fun put(key: String, value: String)

    suspend fun remove(key: String)

    /** Keys another copy of the app changed, as [KeyValueStore.changedElsewhere]: another browser tab on the web, nothing elsewhere. */
    val changedElsewhere: Flow<String?> get() = emptyFlow()
}

/** Secrets that last until the app quits. For tests, and for platforms whose secure store comes later (Android, Apple: M4). */
class InMemorySecretStore : SecretStore {
    private val values = mutableMapOf<String, String>()

    override val storage: StateFlow<SecretStorage> = MutableStateFlow(SecretStorage.Memory).asStateFlow()

    override suspend fun get(key: String): String? = values[key]

    override suspend fun put(key: String, value: String) {
        values[key] = value
    }

    override suspend fun remove(key: String) {
        values.remove(key)
    }
}
