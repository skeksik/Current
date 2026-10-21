package com.skeksik.current.core.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where requests to one server go right now. A server can have several addresses (local and internet) and the app moves between them
 * as the network changes (roadmap, M1 server setup), so a [ServerClient] reads [current] for every request instead of fixing a base URL.
 */
interface ServerAddressSource {
    /** The address to use now; null while none is known to work, which fails requests as unreachable. For display and stale reports. */
    val current: StateFlow<ServerUrl?>

    /**
     * The address for a request about to be sent. A source that is still checking where the server is (at start, after a network
     * change) makes the request wait, for a bounded time, instead of sending it to an unproved address. Null fails it as unreachable.
     */
    suspend fun awaitCurrent(): ServerUrl? = current.value

    /**
     * A request to [address] could not connect or timed out. The source may switch to another address; the failed request is not
     * retried. A report for an address that is no longer in use is stale and should be ignored.
     */
    fun reportUnreachable(address: ServerUrl)

    /**
     * A request to [address] succeeded, so the server is reachable there. Only clears an offline state for the address in use; it never
     * proves an address (credentials still go only where the source sent them). A report for another address is ignored.
     */
    fun reportReachable(address: ServerUrl) = Unit
}

/** A server with one address that never changes. */
class FixedServerAddress(address: ServerUrl) : ServerAddressSource {
    override val current: StateFlow<ServerUrl?> = MutableStateFlow(address).asStateFlow()

    override fun reportUnreachable(address: ServerUrl) = Unit
}
