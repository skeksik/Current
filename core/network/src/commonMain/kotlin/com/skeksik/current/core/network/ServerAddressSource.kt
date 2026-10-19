package com.skeksik.current.core.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where requests to one server go right now. A server can have several addresses (local and internet) and the app moves between them
 * as the network changes (roadmap, M1 server setup), so a [ServerClient] reads [current] for every request instead of fixing a base URL.
 */
interface ServerAddressSource {
    /** The address to use now; null while none is known to work, which fails requests as unreachable. */
    val current: StateFlow<ServerUrl?>

    /**
     * A request to [address] could not connect. The source may switch to another address; the failed request is not retried. A report
     * for an address that is no longer [current] is stale and should be ignored.
     */
    fun reportUnreachable(address: ServerUrl)
}

/** A server with one address that never changes. */
class FixedServerAddress(address: ServerUrl) : ServerAddressSource {
    override val current: StateFlow<ServerUrl?> = MutableStateFlow(address).asStateFlow()

    override fun reportUnreachable(address: ServerUrl) = Unit
}
