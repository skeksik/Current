package com.skeksik.current.core.database

import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import org.koin.dsl.module

/** Koin definitions of `core:database`. On Android the shell must provide the application `Context`. */
val databaseModule = module {
    single { platformKeyValueStore() }
    single(LOCAL) { platformLocalStore() }
    single { DeviceIdentity.load(get(LOCAL), platformDeviceName()) }
    single { platformSecretStore() }
}

/** The machine-local store: values that must never roam or be restored on another device (ADR 0017). */
internal val LOCAL = named("local")

/** Settings. Desktop: a JSON file in the app's config folder; web: `localStorage`; Android: `SharedPreferences`; Apple: `NSUserDefaults`. */
internal expect fun Scope.platformKeyValueStore(): KeyValueStore

/** Desktop: a JSON file in the machine-local folder (`%LOCALAPPDATA%`, `$XDG_STATE_HOME`); elsewhere the settings store's own kind. */
internal expect fun Scope.platformLocalStore(): KeyValueStore

/** What server dashboards show for this device: the computer's name on desktop, browser and OS on the web. */
internal expect fun platformDeviceName(): String

/** Desktop: Credential Manager or the Secret Service; web: `localStorage`; Android and Apple: memory until M4. */
internal expect fun Scope.platformSecretStore(): SecretStore
