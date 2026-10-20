package com.skeksik.current.core.database

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.scope.Scope

internal actual fun Scope.platformKeyValueStore(): KeyValueStore = LocalStorageKeyValueStore()

/** A browser profile does not roam the way an app folder does, so the machine-local store is `localStorage` too, under its own prefix. */
internal actual fun Scope.platformLocalStore(): KeyValueStore = LocalStorageKeyValueStore("current.local.")

internal actual fun Scope.platformSecretStore(): SecretStore = LocalStorageSecretStore()

/** "Chrome on Windows", from the user agent; good enough for a server dashboard. */
internal actual fun platformDeviceName(): String = browserDeviceName(userAgent())

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

/**
 * Secrets in `localStorage` under `current.secret.` (ADR 0017): they survive a restart, which the web app needs to stay signed in, and
 * any script running on the origin can read them. A strict Content-Security-Policy keeps foreign scripts out.
 */
class LocalStorageSecretStore : SecretStore {
    private val store = LocalStorageKeyValueStore("current.secret.")

    override val storage: StateFlow<SecretStorage> = MutableStateFlow(SecretStorage.Browser).asStateFlow()

    override suspend fun get(key: String): String? = store.get(key)

    override suspend fun put(key: String, value: String) = store.put(key, value)

    override suspend fun remove(key: String) = store.remove(key)
}

private fun userAgent(): String = js("(typeof navigator !== 'undefined' && navigator.userAgent) || ''")
