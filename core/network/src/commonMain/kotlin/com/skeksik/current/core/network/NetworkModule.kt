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

/**
 * A bug that ends background work is logged by class name only, since its message may quote a URL or a body (ADR 0015). Without a
 * handler it would end the app on Android and Apple; with it, a roaming source starts again ([RoamingAddressSources]).
 */
private val logBackgroundBug = CoroutineExceptionHandler { _, error -> logBug("Background work ended by a bug", error) }

/** Logs what happened and the error's class name, never its message (ADR 0015). */
internal fun logBug(what: String, error: Throwable) = println("$what: ${error::class.simpleName}")

/** Koin definitions of `core:network`. Needs `core:database`'s `databaseModule` for the saved servers. */
val networkModule = module {
    single<CoroutineScope>(AppScope) {
        CoroutineScope(SupervisorJob() + Dispatchers.Default + logBackgroundBug)
    } withOptions { onClose { it?.cancel() } }
    single { createHttpClient() } withOptions { onClose { it?.close() } }
    single { MutableCapabilitiesState() } bind CapabilitiesState::class
    single { NetworkLogger.None }
    single { ServerClients(get(), get()) }
    single<NetworkChanges> { platformNetworkChanges() }
    // Resolved when the first data layer needs it, after every module's probes are defined.
    single<ServerAddressSources> { RoamingAddressSources(get(), getAll(), get(), get(), get(AppScope)) }
    // Created at start so the saved servers are in the capabilities state before any feature reads it.
    single<ServerRepository>(createdAtStart = true) { StoredServerRepository(get(), get()) }
}
