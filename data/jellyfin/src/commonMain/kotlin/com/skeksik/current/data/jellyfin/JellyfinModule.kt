package com.skeksik.current.data.jellyfin

import com.skeksik.current.core.network.AppScope
import com.skeksik.current.core.network.ServerProbe
import org.koin.dsl.module

/** Koin definitions of `data:jellyfin`. Needs `core:database`'s and `core:network`'s modules. */
val jellyfinModule = module {
    single<ServerProbe> { JellyfinServerProbe(get()) }
    // Created at start so saved sessions are restored before any screen reads the capabilities state.
    single<JellyfinAccounts>(createdAtStart = true) {
        StoredJellyfinAccounts(
            servers = get(),
            addresses = get(),
            clients = get(),
            device = get(),
            secrets = get(),
            settings = get(),
            capabilities = get(),
            scope = get(AppScope),
        ).also { it.restore() }
    }
}
