package com.skeksik.current.core.network

import com.skeksik.current.core.model.SavedServer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/** When a [RoamingServerAddress] asks its addresses again, besides network changes and failed requests (ADR 0019). */
data class RoamingTiming(
    /** How often the address in use and the ones above it are asked: to move back to a local address, or notice a dead one. */
    val recheckEvery: Duration = 5.minutes,
    /** While no address answers: the first wait before asking again, doubled after each miss up to [retryAtMost]. */
    val retryFirst: Duration = 15.seconds,
    val retryAtMost: Duration = 5.minutes,
    /**
     * How long a request waits for a check in progress (at start, after a network change). Null: twice the probe's timeout, room for
     * a check that a network change restarts.
     */
    val requestWaitsAtMost: Duration? = null,
)

/** A [ServerAddressSource] that [RoamingAddressSources] keeps running for one saved server. */
internal interface ManagedServerAddress : ServerAddressSource {
    /** The saved server changed: addresses added, removed or reordered. */
    fun update(server: SavedServer)

    /** Chooses addresses until cancelled; afterwards the source has no address. */
    suspend fun run(networkChanges: Flow<Unit>)
}

/**
 * Uses the first address, in the user's priority order, at which the saved server answers (ADR 0019). Every address is asked at
 * once through [probe], without credentials, and only one that reports the saved server id is ever used.
 *
 * At start and after a network change, requests wait ([awaitCurrent]) until a check has proved an address: on a new network, whatever
 * answers at the old address is not proved to be the server. Other checks (a failed request, an edit, the periodic check) keep the
 * address in use until they find another; when nothing answers, the last proved address stays in use and the server is marked
 * unreachable. Only a network change, or an edit that removes it, clears it. A check made stale by either is cancelled.
 */
internal class RoamingServerAddress(
    server: SavedServer,
    private val probe: ServerProbe,
    private val capabilities: MutableCapabilitiesState,
    private val timing: RoamingTiming = RoamingTiming(),
) : ManagedServerAddress {
    /** Everything a check's result depends on, changed in one step so that a result never lands on edited addresses. */
    private data class Roam(
        val server: SavedServer,
        /** The proved address requests go to; null while none is known. */
        val address: ServerUrl?,
        /** Requests wait: a check runs whose answer they need (start, network change). */
        val blocking: Boolean,
        /** Bumped by every edit and network change; a check started under another generation is thrown away. */
        val generation: Int,
    )

    private val state = MutableStateFlow(Roam(server, address = null, blocking = true, generation = 0))
    private val published = MutableStateFlow<ServerUrl?>(null)

    override val current: StateFlow<ServerUrl?> = published.asStateFlow()

    /** A full check is due. Conflated: any number of requests while one runs make one more. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val requestWaitsAtMost = timing.requestWaitsAtMost ?: (probe.timeout * 2)

    override suspend fun awaitCurrent(): ServerUrl? =
        withTimeoutOrNull(requestWaitsAtMost) { state.first { !it.blocking } }?.address

    override fun reportUnreachable(address: ServerUrl) {
        if (address == state.value.address) wake.trySend(Unit)
    }

    override fun update(server: SavedServer) {
        var edited = false
        state.update { roam ->
            edited = server.addresses != roam.server.addresses
            if (!edited) return@update roam.copy(server = server)
            // A removed address gets nothing more, not even until the check below has run.
            roam.copy(server = server, address = roam.address?.takeIf { it in server.urls() }, generation = roam.generation + 1)
        }
        if (edited) wake.trySend(Unit)
    }

    private fun networkChanged() {
        state.update { it.copy(address = null, blocking = true, generation = it.generation + 1) }
        wake.trySend(Unit)
    }

    override suspend fun run(networkChanges: Flow<Unit>) {
        try {
            coroutineScope {
                launch { state.collect { published.value = it.address } }
                launch { networkChanges.collect { networkChanged() } }
                roam()
            }
        } finally {
            state.update { it.copy(address = null, blocking = false) }
            published.value = null
        }
    }

    private suspend fun roam(): Nothing {
        var full = true
        var retry = timing.retryFirst
        while (true) {
            val start = state.value
            full = full || start.address == null
            val addresses = start.server.urls()
            // The periodic check asks the address in use and those above it; all of them only when none of those answers.
            val candidates = if (full) addresses else addresses.subList(0, addresses.indexOf(start.address) + 1)
            val checked = unlessStale(start.generation) { firstAnswering(start.server, candidates) }
            var applied = false
            if (checked != null) {
                state.update { now ->
                    applied = now.generation == start.generation
                    when {
                        !applied -> now
                        checked.found != null -> now.copy(address = checked.found, blocking = false)
                        // A miss keeps the last proved address: only a network change or an edit that removes it clears it.
                        else -> now.copy(blocking = false)
                    }
                }
            }
            if (!applied) {
                // An edit or a network change made the check stale; its wake is consumed by asking again now.
                wake.tryReceive()
                full = true
                continue
            }
            val found = checked?.found
            if (found == null && !full) {
                full = true
                continue
            }
            capabilities.update(start.server.ref) { it.copy(reachable = found != null) }
            val wait = if (found == null) {
                retry.also { retry = (retry * 2).coerceAtMost(timing.retryAtMost) }
            } else {
                retry = timing.retryFirst
                timing.recheckEvery
            }
            val woken = withTimeoutOrNull(wait) { wake.receive() } != null
            full = woken || found == null
        }
    }

    private class Checked(val found: ServerUrl?)

    /** Runs [check], or gives up on it as soon as an edit or a network change moves past [generation]; null then. */
    private suspend fun unlessStale(generation: Int, check: suspend () -> ServerUrl?): Checked? = coroutineScope {
        val stale = async { state.first { it.generation != generation } }
        val work = async { Checked(check()) }
        select {
            work.onAwait { it }
            stale.onAwait { null }
        }.also {
            stale.cancel()
            work.cancel()
        }
    }

    /**
     * Asks every candidate at once; the first, in priority order, that reports the saved server id. A higher address is waited for
     * until it answers or fails, which the probe's own timeout bounds.
     */
    private suspend fun firstAnswering(server: SavedServer, candidates: List<ServerUrl>): ServerUrl? = coroutineScope {
        val attempts = candidates.map { url -> async { isSavedServer(server, url) } }
        val index = attempts.indexOfFirst { it.await() }
        attempts.forEach { it.cancel() }
        candidates.getOrNull(index)
    }

    private suspend fun isSavedServer(server: SavedServer, url: ServerUrl): Boolean = try {
        probe.identify(server.ref, url).serverId == server.serverId
    } catch (e: ServiceException) {
        false
    }
}

/**
 * For a service that cannot be identified without credentials (Stash, later): its addresses in priority order, moving to the next on a
 * reported failure and back to the first on a network change. Every saved address is one the user typed or confirmed (ADR 0016).
 */
internal class OrderedServerAddress(server: SavedServer) : ManagedServerAddress {
    private data class Ordered(val addresses: List<ServerUrl>, val address: ServerUrl?)

    private val state = MutableStateFlow(Ordered(server.urls(), server.urls().firstOrNull()))
    private val published = MutableStateFlow(state.value.address)

    override val current: StateFlow<ServerUrl?> = published.asStateFlow()

    override suspend fun awaitCurrent(): ServerUrl? = state.value.address

    override fun reportUnreachable(address: ServerUrl) = state.update { ordered ->
        if (ordered.address != address) return@update ordered
        ordered.copy(address = ordered.addresses.getOrNull((ordered.addresses.indexOf(address) + 1) % ordered.addresses.size))
    }

    override fun update(server: SavedServer) = state.update { ordered ->
        if (server.urls() == ordered.addresses) ordered else Ordered(server.urls(), server.urls().firstOrNull())
    }

    override suspend fun run(networkChanges: Flow<Unit>) {
        try {
            coroutineScope {
                launch { state.collect { published.value = it.address } }
                networkChanges.collect { state.update { it.copy(address = it.addresses.firstOrNull()) } }
            }
        } finally {
            state.update { it.copy(addresses = emptyList(), address = null) }
            published.value = null
        }
    }
}

private fun SavedServer.urls(): List<ServerUrl> = addresses.mapNotNull { ServerUrl.parse(it.url) }
