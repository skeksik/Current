package com.skeksik.current.core.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.core.module.dsl.onClose
import org.koin.core.module.dsl.withOptions
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * A scope that lives as long as the app, for background work no screen owns (restoring sessions, handling 401s). Declared before the
 * module, which Koin builds at once.
 */
val AppScope = named("appScope")

/** Koin definitions of `core:network`. Needs `core:database`'s `databaseModule` for the saved servers. */
val networkModule = module {
    single<CoroutineScope>(AppScope) { CoroutineScope(SupervisorJob() + Dispatchers.Default) } withOptions { onClose { it?.cancel() } }
    single { createHttpClient() } withOptions { onClose { it?.close() } }
    single { MutableCapabilitiesState() } bind CapabilitiesState::class
    single { NetworkLogger.None }
    single { ServerClients(get(), get()) }
    single<ServerAddressSources> { FirstAddressSources() }
    // Android's shell binds AndroidLocalNetworkAccess over this (ADR 0020).
    single<LocalNetworkAccess> { LocalNetworkAccess.NotNeeded }
    // Created at start so the saved servers are in the capabilities state before any feature reads it.
    single<ServerRepository>(createdAtStart = true) { StoredServerRepository(get(), get()) }
}
