package com.skeksik.current.core.network

import kotlinx.coroutines.CoroutineExceptionHandler
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
    // A bug that ends background work is logged by class name only (ADR 0015). Without a handler it would end the app on Android and
    // Apple; with it, a roaming source starts again (RoamingAddressSources).
    single<CoroutineScope>(AppScope) {
        val log = get<NetworkLogger>()
        val logBackgroundBug = CoroutineExceptionHandler { _, error -> log.bug("Background work ended by a bug", error) }
        CoroutineScope(SupervisorJob() + Dispatchers.Default + logBackgroundBug)
    } withOptions { onClose { it?.cancel() } }
    single { createHttpClient() } withOptions { onClose { it?.close() } }
    single { MutableCapabilitiesState() } bind CapabilitiesState::class
    // The request log, in memory ("Copy diagnostics") and printed where the platform's flag allows.
    single { RequestLogBuffer(echo = platformLogEcho(this)) }
    single<NetworkLogger> { get<RequestLogBuffer>() }
    single<RequestLogHistory> { get<RequestLogBuffer>() }
    single { platformInfo() }
    single { ServerClients(get(), get()) }
    single<NetworkChanges> { platformNetworkChanges() }
    // Resolved when the first data layer needs it, after every module's probes are defined.
    single<ServerAddressSources> {
        RoamingAddressSources(get(), getAll(), get(), get(), get(AppScope), localAccess = get(), log = get())
    }
    single<LocalNetworkAccess> { platformLocalNetworkAccess() }
    // Created at start so the saved servers are in the capabilities state before any feature reads it.
    single<ServerRepository>(createdAtStart = true) { StoredServerRepository(get(), get()) }
}
