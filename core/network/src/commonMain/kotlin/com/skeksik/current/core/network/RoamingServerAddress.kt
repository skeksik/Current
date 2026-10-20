package com.skeksik.current.core.network

import com.skeksik.current.core.model.SavedServer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/** When a [RoamingServerAddress] asks its addresses again, besides network changes and failed requests (ADR 0019). */
data class RoamingTiming(
    /** While a lower-priority address is in use, how often the ones above it are asked, to move back to a local address. */
    val recheckEvery: Duration = 5.minutes,
    /** While no address answers: the first wait before asking again, doubled after each miss up to [retryAtMost]. */
    val retryFirst: Duration = 15.seconds,
    val retryAtMost: Duration = 5.minutes,
)

/** A [ServerAddressSource] that [RoamingAddressSources] keeps running for one saved server. */
internal interface ManagedServerAddress : ServerAddressSource {
    /** The saved server changed: addresses added, removed or reordered. */
    fun update(server: SavedServer)

    /** Chooses addresses until cancelled. */
    suspend fun run(networkChanges: Flow<Unit>)
}

/**
 * Uses the first address, in the user's priority order, at which the saved server answers (ADR 0019). Every address is asked at
 * once through [probe], without credentials, and only one that reports the saved server id is ever used, so credentials never go to
 * another server. It asks again at start, when the addresses change, on a network change, when a request reports the current address
 * unreachable, while nothing answers (with growing waits), and periodically for the addresses above the current one.
 *
 * [current] keeps the last good address while a check runs, and is null only when nothing answered. Whether the server answered goes
 * into the capabilities state.
 */
internal class RoamingServerAddress(
    server: SavedServer,
    private val probe: ServerProbe,
    private val capabilities: MutableCapabilitiesState,
    private val timing: RoamingTiming = RoamingTiming(),
    /** Waits between checks; tests pass their own clock. */
    private val sleep: suspend (Duration) -> Unit = { delay(it) },
) : ManagedServerAddress {
    private val saved = MutableStateFlow(server)
    private val state = MutableStateFlow<ServerUrl?>(null)

    override val current: StateFlow<ServerUrl?> = state.asStateFlow()

    /** A full check is due. Conflated: any number of requests while one runs make one more. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    override fun reportUnreachable(address: ServerUrl) {
        if (address == state.value) wake.trySend(Unit)
    }

    override fun update(server: SavedServer) {
        if (server.addresses == saved.value.addresses) return
        saved.value = server
        // A removed address gets nothing more, not even until the check below has run.
        state.update { url -> url?.takeIf { it in server.urls() } }
        wake.trySend(Unit)
    }

    override suspend fun run(networkChanges: Flow<Unit>): Unit = coroutineScope {
        launch { networkChanges.collect { wake.trySend(Unit) } }
        var full = true
        var retry = timing.retryFirst
        while (true) {
            val using = state.value
            full = full || using == null
            val addresses = saved.value.urls()
            val found = firstAnswering(if (full) addresses else addresses.takeWhile { it != using })
            when {
                found != null && found in saved.value.urls() -> {
                    state.value = found
                    retry = timing.retryFirst
                    capabilities.update(saved.value.ref) { it.copy(reachable = true) }
                }
                // The addresses changed during the check; the wake from update asks again.
                found != null -> Unit
                full -> {
                    state.value = null
                    capabilities.update(saved.value.ref) { it.copy(reachable = false) }
                }
            }
            val now = state.value
            val wait = when {
                now == null -> retry.also { retry = (retry * 2).coerceAtMost(timing.retryAtMost) }
                now != saved.value.urls().firstOrNull() -> timing.recheckEvery
                else -> null
            }
            full = awaitWake(wait) || now == null
        }
    }

    /** True when woken, false when [timeout] passed first. Without a timeout, waits for a wake. */
    private suspend fun awaitWake(timeout: Duration?): Boolean {
        if (timeout == null) {
            wake.receive()
            return true
        }
        return coroutineScope {
            val timer = async { sleep(timeout) }
            select {
                wake.onReceive { true }
                timer.onAwait { false }
            }.also { timer.cancel() }
        }
    }

    /**
     * Asks every candidate at once; the first, in priority order, that reports the saved server id. A higher address is waited for
     * until it answers or fails, which the probe's own timeout bounds.
     */
    private suspend fun firstAnswering(candidates: List<ServerUrl>): ServerUrl? = coroutineScope {
        val attempts = candidates.map { url -> async { isSavedServer(url) } }
        val index = attempts.indexOfFirst { it.await() }
        attempts.forEach { it.cancel() }
        candidates.getOrNull(index)
    }

    private suspend fun isSavedServer(url: ServerUrl): Boolean = try {
        probe.identify(saved.value.ref, url).serverId == saved.value.serverId
    } catch (e: ServiceException) {
        false
    }
}

/**
 * For a service that cannot be identified without credentials (Stash, later): its addresses in priority order, moving to the next on a
 * reported failure and back to the first on a network change. Every saved address is one the user typed or confirmed (ADR 0016).
 */
internal class OrderedServerAddress(server: SavedServer) : ManagedServerAddress {
    private var addresses = server.urls()
    private val state = MutableStateFlow(addresses.firstOrNull())

    override val current: StateFlow<ServerUrl?> = state.asStateFlow()

    override fun reportUnreachable(address: ServerUrl) = state.update { url ->
        if (url != address) url else addresses.getOrNull((addresses.indexOf(address) + 1) % addresses.size)
    }

    override fun update(server: SavedServer) {
        if (server.urls() == addresses) return
        addresses = server.urls()
        state.value = addresses.firstOrNull()
    }

    override suspend fun run(networkChanges: Flow<Unit>): Unit = networkChanges.collect { state.value = addresses.firstOrNull() }
}

private fun SavedServer.urls(): List<ServerUrl> = addresses.mapNotNull { ServerUrl.parse(it.url) }
