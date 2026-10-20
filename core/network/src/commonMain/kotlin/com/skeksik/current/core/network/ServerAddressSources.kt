package com.skeksik.current.core.network

import com.skeksik.current.core.model.SavedServer
import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServiceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

/** Chooses how a saved server is reached: [RoamingAddressSources] in the app (ADR 0019). */
fun interface ServerAddressSources {
    fun forServer(server: SavedServer): ServerAddressSource
}

/** The first address in the user's priority order, always. For tests that need no roaming. */
class FirstAddressSources : ServerAddressSources {
    override fun forServer(server: SavedServer): ServerAddressSource {
        val url = server.addresses.firstNotNullOfOrNull { ServerUrl.parse(it.url) }
            ?: error("Saved server ${server.ref.key} has no usable address")
        return FixedServerAddress(url)
    }
}

/**
 * One roaming address per saved server, started for every saved server, shared by every client of that server and running in [scope]
 * until the server is removed (ADR 0019). A service with a [ServerProbe] gets a [RoamingServerAddress]. A service in [unidentifiable],
 * which cannot prove who it is without credentials, gets an [OrderedServerAddress]. Any other server, and one that is not saved, gets
 * no address, so requests to it fail as unreachable.
 */
class RoamingAddressSources internal constructor(
    private val servers: ServerRepository,
    probes: List<ServerProbe>,
    private val capabilities: MutableCapabilitiesState,
    networkChanges: NetworkChanges,
    private val scope: CoroutineScope,
    private val timing: RoamingTiming = RoamingTiming(),
    private val unidentifiable: Set<ServiceType> = setOf(ServiceType.stash),
) : ServerAddressSources {
    private class Running(val source: ManagedServerAddress, val job: Job)

    private val probes = probes.associateBy { it.service }
    private val changes = networkChanges.changes().shareIn(scope, SharingStarted.WhileSubscribed())
    private val running = MutableStateFlow<Map<ServerRef, Running>>(emptyMap())

    init {
        scope.launch { servers.servers.collect { sync() } }
    }

    override fun forServer(server: SavedServer): ServerAddressSource =
        running.value[server.ref]?.source ?: servers.server(server.ref)?.let(::start) ?: NoAddress

    private fun start(server: SavedServer): ServerAddressSource {
        val source = when {
            server.ref.service in probes -> RoamingServerAddress(server, probes.getValue(server.ref.service), capabilities, timing)
            server.ref.service in unidentifiable -> OrderedServerAddress(server)
            else -> return NoAddress
        }
        val made = Running(source, scope.launch(start = CoroutineStart.LAZY) { source.run(changes) })
        // Two callers may race to start the same server's source: one wins, the other's never started job is dropped.
        val winner = running.updateAndGet { if (server.ref in it) it else it + (server.ref to made) }.getValue(server.ref)
        if (winner !== made) {
            made.job.cancel()
            return winner.source
        }
        // A source that stopped, for whatever reason, is forgotten, so the next caller starts a new one.
        made.job.invokeOnCompletion { running.update { map -> if (map[server.ref] === made) map - server.ref else map } }
        made.job.start()
        return source
    }

    /** Follows the saved servers as they are now, not as a possibly stale emission listed them. */
    private fun sync() {
        val removed = running.value.filterKeys { servers.server(it) == null }
        running.update { map -> map.filterNot { (ref, run) -> removed[ref] === run } }
        removed.values.forEach { it.job.cancel() }
        running.value.forEach { (ref, run) -> servers.server(ref)?.let(run.source::update) }
        servers.servers.value.filter { it.ref !in running.value }.forEach(::start)
    }

    private object NoAddress : ServerAddressSource {
        override val current: StateFlow<ServerUrl?> = MutableStateFlow(null)

        override fun reportUnreachable(address: ServerUrl) = Unit
    }
}
