package com.skeksik.current.core.network

import org.koin.core.module.dsl.onClose
import org.koin.core.module.dsl.withOptions
import org.koin.dsl.bind
import org.koin.dsl.module

/** Koin definitions of `core:network`. */
val networkModule = module {
    single { createHttpClient() } withOptions { onClose { it?.close() } }
    single { MutableCapabilitiesState() } bind CapabilitiesState::class
    single { NetworkLogger.None }
    single { ServerClients(get(), get()) }
}
