package com.skeksik.current.core.database

/**
 * Small settings that must survive a restart, such as the saved servers (ADR 0016). Strings only, read and written synchronously;
 * callers serialise their own values. Not for secrets: tokens and keys go to secure storage, and on the web this is plain
 * `localStorage`.
 */
interface KeyValueStore {
    fun get(key: String): String?

    fun put(key: String, value: String)

    fun remove(key: String)
}

/** Forgets everything when the app stops. For tests, and for platforms whose store is not available. */
class InMemoryKeyValueStore(initial: Map<String, String> = emptyMap()) : KeyValueStore {
    private val values = initial.toMutableMap()

    override fun get(key: String): String? = values[key]

    override fun put(key: String, value: String) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }
}
