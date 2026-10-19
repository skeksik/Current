package com.skeksik.current.data.jellyfin

import com.skeksik.current.core.network.ServerProbe
import org.koin.dsl.module

/** Koin definitions of `data:jellyfin`. Needs `core:network`'s `networkModule`. */
val jellyfinModule = module {
    single<ServerProbe> { JellyfinServerProbe(get()) }
}
