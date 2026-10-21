package com.skeksik.current.core.network

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

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

    /** Whether the source is asking its addresses where the server is now (a check, or the wait for a network to settle). For display. */
    val checking: StateFlow<Boolean> get() = NotChecking

    /**
     * Each address roaming switched to from another address it used before: after a network change, a resumed network, a failed
     * request, a timer or a refresh, never because the user edited the addresses. Neither the first address after start nor any the
     * first check moves through is a switch. For a brief notice; a collector that falls behind may miss some.
     */
    val switches: Flow<ServerUrl> get() = emptyFlow()

    /** The user asked where the server is now: a source that checks starts a full check, unless one is running or about to. */
    fun refresh() = Unit
}

private val NotChecking: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

/** A server with one address that never changes. */
class FixedServerAddress(address: ServerUrl) : ServerAddressSource {
    override val current: StateFlow<ServerUrl?> = MutableStateFlow(address).asStateFlow()

    override fun reportUnreachable(address: ServerUrl) = Unit
}
