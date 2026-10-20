package com.skeksik.current.core.network

import com.skeksik.current.core.model.SavedServer
import com.skeksik.current.core.model.ServerRef
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
 * One roaming address per saved server, shared by every client of that server and running in [scope] until the server is removed
 * (ADR 0019). A service with a [ServerProbe] gets a [RoamingServerAddress]; one without gets an [OrderedServerAddress]. A server
 * that is not saved gets no address, so requests to it fail as unreachable.
 */
class RoamingAddressSources internal constructor(
    private val servers: ServerRepository,
    probes: List<ServerProbe>,
    private val capabilities: MutableCapabilitiesState,
    networkChanges: NetworkChanges,
    private val scope: CoroutineScope,
    private val timing: RoamingTiming = RoamingTiming(),
) : ServerAddressSources {
    private class Running(val source: ManagedServerAddress, val job: Job)

    private val probes = probes.associateBy { it.service }
    private val changes = networkChanges.changes().shareIn(scope, SharingStarted.WhileSubscribed())
    private val running = MutableStateFlow<Map<ServerRef, Running>>(emptyMap())

    init {
        scope.launch { servers.servers.collect(::sync) }
    }

    override fun forServer(server: SavedServer): ServerAddressSource {
        running.value[server.ref]?.let { return it.source }
        val saved = servers.server(server.ref) ?: return NoAddress
        val made = start(saved)
        // Two callers may race to make the same server's source: one wins, the other's never started job is dropped.
        val winner = running.updateAndGet { if (saved.ref in it) it else it + (saved.ref to made) }.getValue(saved.ref)
        if (winner === made) made.job.start() else made.job.cancel()
        return winner.source
    }

    private fun start(server: SavedServer): Running {
        val probe = probes[server.ref.service]
        val source = if (probe == null) OrderedServerAddress(server) else RoamingServerAddress(server, probe, capabilities, timing)
        return Running(source, scope.launch(start = CoroutineStart.LAZY) { source.run(changes) })
    }

    private fun sync(saved: List<SavedServer>) {
        val byRef = saved.associateBy { it.ref }
        running.value.forEach { (ref, run) -> byRef[ref]?.let(run.source::update) }
        running.update { map ->
            map.filter { (ref, run) -> (ref in byRef).also { kept -> if (!kept) run.job.cancel() } }
        }
    }

    private object NoAddress : ServerAddressSource {
        override val current: StateFlow<ServerUrl?> = MutableStateFlow(null)

        override fun reportUnreachable(address: ServerUrl) = Unit
    }
}
