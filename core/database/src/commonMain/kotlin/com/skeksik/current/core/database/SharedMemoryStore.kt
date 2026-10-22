package com.skeksik.current.core.database

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One store in memory shared by several copies of the app, as browser tabs share `localStorage`. For tests: each view reports the
 * writes the other views make through `changedElsewhere`, never its own, as the browser's `storage` event does.
 */
class SharedMemoryStore {
    private val values = mutableMapOf<String, String>()
    private val views = mutableListOf<View>()

    /** One copy's settings store. */
    fun settings(): KeyValueStore = View().also { views += it }

    /** One copy's secret store. */
    fun secrets(): SecretStore = SecretView(View().also { views += it })

    /** Another program clears the whole store, as `localStorage.clear()` in a tab does: every view hears null. */
    fun clear() {
        values.clear()
        views.forEach { it.heard.tryEmit(null) }
    }

    private inner class View : KeyValueStore {
        val heard = MutableSharedFlow<String?>(extraBufferCapacity = 64)

        override val changedElsewhere: Flow<String?> = heard.asSharedFlow()

        override fun get(key: String): String? = values[key]

        override fun put(key: String, value: String) {
            if (values.put(key, value) != value) tellOthers(key)
        }

        override fun remove(key: String) {
            if (values.remove(key) != null) tellOthers(key)
        }

        private fun tellOthers(key: String) = views.filter { it !== this }.forEach { it.heard.tryEmit(key) }
    }

    private class SecretView(private val store: View) : SecretStore {
        override val storage: StateFlow<SecretStorage> = MutableStateFlow(SecretStorage.Browser).asStateFlow()

        override val changedElsewhere: Flow<String?> = store.changedElsewhere

        override suspend fun get(key: String): String? = store.get(key)

        override suspend fun put(key: String, value: String) = store.put(key, value)

        override suspend fun remove(key: String) = store.remove(key)
    }
}
