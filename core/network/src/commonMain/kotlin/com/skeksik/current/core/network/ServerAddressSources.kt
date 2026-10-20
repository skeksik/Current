package com.skeksik.current.core.network

import com.skeksik.current.core.model.SavedServer

/** Chooses how a saved server is reached. Roaming between its addresses (roadmap, M1) replaces [FirstAddressSources] here, and only here. */
fun interface ServerAddressSources {
    fun forServer(server: SavedServer): ServerAddressSource
}

/** The first address in the user's priority order, always. */
class FirstAddressSources : ServerAddressSources {
    override fun forServer(server: SavedServer): ServerAddressSource {
        val url = server.addresses.firstNotNullOfOrNull { ServerUrl.parse(it.url) }
            ?: error("Saved server ${server.ref.key} has no usable address")
        return FixedServerAddress(url)
    }
}
