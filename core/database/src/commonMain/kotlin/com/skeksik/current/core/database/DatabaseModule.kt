package com.skeksik.current.core.database

import org.koin.core.scope.Scope
import org.koin.dsl.module

/** Koin definitions of `core:database`. On Android the shell must provide the application `Context`. */
val databaseModule = module {
    single { platformKeyValueStore() }
}

/** Desktop: a JSON file in the app's config folder; web: `localStorage`; Android: `SharedPreferences`; Apple: `NSUserDefaults`. */
internal expect fun Scope.platformKeyValueStore(): KeyValueStore
