package com.skeksik.current.core.database

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Small settings that must survive a restart, such as the saved servers (ADR 0016). Strings only, read and written synchronously;
 * callers serialise their own values. Not for secrets: tokens and keys go to secure storage, and on the web this is plain
 * `localStorage`.
 */
interface KeyValueStore {
    fun get(key: String): String?

    fun put(key: String, value: String)

    fun remove(key: String)

    /**
     * Keys another copy of the app changed in this store: another browser tab on the web, which shares `localStorage`. Never this copy's
     * own writes. Null means the whole store may have changed (cleared). Empty where nothing else shares the store.
     */
    val changedElsewhere: Flow<String?> get() = emptyFlow()
}

/**
 * [fullKey] as the store with [prefix] names it, or null when it belongs to another store: a key without [prefix], or one under a
 * longer prefix from [prefixes] nested in it (`current.` holds neither `current.local.` nor `current.secret.` keys).
 */
internal fun keyInStore(fullKey: String, prefix: String, prefixes: List<String>): String? {
    if (!fullKey.startsWith(prefix)) return null
    if (prefixes.any { it.length > prefix.length && it.startsWith(prefix) && fullKey.startsWith(it) }) return null
    return fullKey.removePrefix(prefix)
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
