package com.skeksik.current.core.database

import org.koin.core.scope.Scope
import platform.Foundation.NSUserDefaults

internal actual fun Scope.platformKeyValueStore(): KeyValueStore = UserDefaultsKeyValueStore()

/** The app's standard `NSUserDefaults`, with keys prefixed `current.`. */
class UserDefaultsKeyValueStore(private val prefix: String = "current.") : KeyValueStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun get(key: String): String? = defaults.stringForKey(prefix + key)

    override fun put(key: String, value: String) = defaults.setObject(value, forKey = prefix + key)

    override fun remove(key: String) = defaults.removeObjectForKey(prefix + key)
}
