package com.skeksik.current.core.database

import android.content.Context
import org.koin.core.scope.Scope

internal actual fun Scope.platformKeyValueStore(): KeyValueStore = SharedPreferencesKeyValueStore(get<Context>())

/** The app's private `SharedPreferences` file `current`. Writes are applied in the background ([android.content.SharedPreferences.Editor.apply]). */
class SharedPreferencesKeyValueStore(context: Context) : KeyValueStore {
    private val preferences = context.getSharedPreferences("current", Context.MODE_PRIVATE)

    override fun get(key: String): String? = preferences.getString(key, null)

    override fun put(key: String, value: String) = preferences.edit().putString(key, value).apply()

    override fun remove(key: String) = preferences.edit().remove(key).apply()
}
