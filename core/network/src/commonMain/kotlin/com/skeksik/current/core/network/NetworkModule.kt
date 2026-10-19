package com.skeksik.current.core.network

import org.koin.core.module.dsl.onClose
import org.koin.core.module.dsl.withOptions
import org.koin.dsl.bind
import org.koin.dsl.module

/** Koin definitions of `core:network`. Needs `core:database`'s `databaseModule` for the saved servers. */
val networkModule = module {
    single { createHttpClient() } withOptions { onClose { it?.close() } }
    single { MutableCapabilitiesState() } bind CapabilitiesState::class
    single { NetworkLogger.None }
    single { ServerClients(get(), get()) }
    // Created at start so the saved servers are in the capabilities state before any feature reads it.
    single<ServerRepository>(createdAtStart = true) { StoredServerRepository(get(), get()) }
}
