package com.skeksik.current.core.database

import android.content.Context
import org.koin.core.scope.Scope

internal actual fun Scope.platformKeyValueStore(): KeyValueStore = SharedPreferencesKeyValueStore(get<Context>())

/** A separate file, `current-local`, kept out of backups (`data_extraction_rules.xml`): a restored phone gets a new device id. */
internal actual fun Scope.platformLocalStore(): KeyValueStore = SharedPreferencesKeyValueStore(get<Context>(), "current-local")

/** Memory until M4 brings a Keystore-backed store. */
internal actual fun Scope.platformSecretStore(): SecretStore = InMemorySecretStore()

internal actual fun platformDeviceName(): String = listOf(android.os.Build.MANUFACTURER, android.os.Build.MODEL)
    .filter { !it.isNullOrBlank() }.distinct().joinToString(" ")

/** The app's private `SharedPreferences` file [name]. Writes are applied in the background ([android.content.SharedPreferences.Editor.apply]). */
class SharedPreferencesKeyValueStore(context: Context, name: String = "current") : KeyValueStore {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun get(key: String): String? = preferences.getString(key, null)

    override fun put(key: String, value: String) = preferences.edit().putString(key, value).apply()

    override fun remove(key: String) = preferences.edit().remove(key).apply()
}
