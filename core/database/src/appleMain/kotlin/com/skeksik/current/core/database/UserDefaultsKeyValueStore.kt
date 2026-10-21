package com.skeksik.current.core.database

import org.koin.core.scope.Scope
import platform.Foundation.NSUserDefaults

internal actual fun Scope.platformKeyValueStore(): KeyValueStore = UserDefaultsKeyValueStore()

/** Under its own prefix. Keeping the device id out of backups and restores (Keychain, this device only) comes with M4. */
internal actual fun Scope.platformLocalStore(): KeyValueStore = UserDefaultsKeyValueStore("current.local.")

/** Memory until M4 brings the Keychain. */
internal actual fun Scope.platformSecretStore(): SecretStore = InMemorySecretStore()

/** The app's standard `NSUserDefaults`, with keys prefixed `current.`. */
class UserDefaultsKeyValueStore(private val prefix: String = "current.") : KeyValueStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun get(key: String): String? = defaults.stringForKey(prefix + key)

    override fun put(key: String, value: String) = defaults.setObject(value, forKey = prefix + key)

    override fun remove(key: String) = defaults.removeObjectForKey(prefix + key)
}
