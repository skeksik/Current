package com.skeksik.current.core.database

import org.koin.core.scope.Scope

internal actual fun Scope.platformKeyValueStore(): KeyValueStore = LocalStorageKeyValueStore()

/**
 * The browser's `localStorage`, with keys prefixed `current.` because the origin may serve other apps. Plain text, readable by any
 * script on the origin: settings only, never tokens. Where storage is unavailable (blocked, or a private window that refuses it),
 * reads return null and writes are dropped, so the app still runs without persistence.
 */
class LocalStorageKeyValueStore(private val prefix: String = "current.") : KeyValueStore {
    override fun get(key: String): String? = storageGet(prefix + key)

    override fun put(key: String, value: String) = storageSet(prefix + key, value)

    override fun remove(key: String) = storageRemove(prefix + key)
}

@Suppress("UNUSED_PARAMETER")
private fun storageGet(key: String): String? = js("(function () { try { return window.localStorage.getItem(key); } catch (e) { return null; } })()")

@Suppress("UNUSED_PARAMETER")
private fun storageSet(key: String, value: String): Unit = js("(function () { try { window.localStorage.setItem(key, value); } catch (e) {} })()")

@Suppress("UNUSED_PARAMETER")
private fun storageRemove(key: String): Unit = js("(function () { try { window.localStorage.removeItem(key); } catch (e) {} })()")
