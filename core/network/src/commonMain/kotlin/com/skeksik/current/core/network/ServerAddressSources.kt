package com.skeksik.current.core.network

import com.skeksik.current.core.model.SavedServer
import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServiceType
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val time: TimeSource = TimeSource.Monotonic,
) : ServerAddressSources {
    /** One run of [source]; [wait] is how long it waited after the last run that a bug ended. */
    private class Running(val source: StableAddress, val job: Job, val wait: Duration) {
        var started: TimeMark? = null
    }

    private val probes = probes.associateBy { it.service }
    private val changes = networkChanges.changes().shareIn(scope, SharingStarted.WhileSubscribed())
    private val running = MutableStateFlow<Map<ServerRef, Running>>(emptyMap())

    init {
        scope.launch { servers.servers.collect { sync() } }
    }

    override fun forServer(server: SavedServer): ServerAddressSource =
        running.value[server.ref]?.source ?: servers.server(server.ref)?.let(::start) ?: NoAddress

    private fun make(server: SavedServer): ManagedServerAddress? = when {
        server.ref.service in probes -> RoamingServerAddress(server, probes.getValue(server.ref.service), capabilities, timing, time)
        server.ref.service in unidentifiable -> OrderedServerAddress(server)
        else -> null
    }

    private fun start(server: SavedServer): ServerAddressSource {
        val source = StableAddress(make(server) ?: return NoAddress)
        val made = runLater(source, wait = Duration.ZERO)
        // Two callers may race to start the same server's source: one wins, the other's never started job is dropped.
        val winner = running.updateAndGet { if (server.ref in it) it else it + (server.ref to made) }.getValue(server.ref)
        if (winner !== made) {
            made.job.cancel()
            return winner.source
        }
        watch(server.ref, made)
        if (servers.server(server.ref) == null) {
            // Removed while this source was being made, after sync looked: it never starts.
            made.job.cancel()
            return NoAddress
        }
        made.job.start()
        return source
    }

    private fun runLater(source: StableAddress, wait: Duration): Running {
        lateinit var run: Running
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(wait)
            run.started = time.markNow()
            source.run(changes)
        }
        run = Running(source, job, wait)
        return run
    }

    /**
     * A run that a bug ended, even with a stray [CancellationException], starts again with a fresh source behind the same
     * [StableAddress], so clients that hold it get an address again; the bug itself still reaches the scope's exception handler. The
     * wait starts at [RoamingTiming.retryFirst] and doubles while the bug recurs, up to [RoamingTiming.retryAtMost]. A run that is
     * stopped (the server removed, the app closing) is forgotten, so the next caller starts a new one.
     */
    private fun watch(ref: ServerRef, run: Running) {
        run.job.invokeOnCompletion { cause ->
            val stopped = cause == null || running.value[ref] !== run || scope.coroutineContext[Job]?.isActive == false
            val saved = servers.server(ref)
            val fresh = saved?.let(::make)
            if (stopped || fresh == null) {
                running.update { map -> if (map[ref] === run) map - ref else map }
                return@invokeOnCompletion
            }
            // The dead source may still hold its run's queued events: requests now wait on the fresh one, which starts cold.
            run.source.replace(fresh)
            servers.server(ref)?.let(fresh::update)
            val longRun = run.started?.let { it.elapsedNow() >= timing.retryAtMost } ?: false
            val wait = if (run.wait == Duration.ZERO || longRun) timing.retryFirst else (run.wait * 2).coerceAtMost(timing.retryAtMost)
            val again = runLater(run.source, wait)
            val replaced = running.updateAndGet { map -> if (map[ref] === run) map + (ref to again) else map }[ref] === again
            if (replaced && servers.server(ref) != null) {
                watch(ref, again)
                again.job.start()
            } else {
                again.job.cancel()
                running.update { map -> if (map[ref] === again) map - ref else map }
            }
        }
    }

    /** Follows the saved servers as they are now, not as a possibly stale emission listed them. */
    private fun sync() {
        val removed = running.value.filterKeys { servers.server(it) == null }
        running.update { map -> map.filterNot { (ref, run) -> removed[ref] === run } }
        removed.values.forEach { it.job.cancel() }
        running.value.forEach { (ref, run) -> servers.server(ref)?.let(run.source::update) }
        servers.servers.value.filter { it.ref !in running.value }.forEach(::start)
    }

    /**
     * What clients of one saved server hold. It stays the same while the source behind it is replaced, so a client keeps working after
     * a bug ended a run, and [ServerRequests] still recognises the address its own request chose.
     */
    private class StableAddress(first: ManagedServerAddress) : ServerAddressSource {
        private val inner = MutableStateFlow(first)
        private val published = MutableStateFlow<ServerUrl?>(null)

        override val current: StateFlow<ServerUrl?> = published.asStateFlow()

        override suspend fun awaitCurrent(): ServerUrl? = inner.value.awaitCurrent()

        override fun reportUnreachable(address: ServerUrl) = inner.value.reportUnreachable(address)

        fun update(server: SavedServer) = inner.value.update(server)

        fun replace(fresh: ManagedServerAddress) {
            inner.value = fresh
        }

        suspend fun run(networkChanges: Flow<NetworkChange>) {
            val source = inner.value
            try {
                coroutineScope {
                    launch { source.current.collect { published.value = it } }
                    source.run(networkChanges)
                }
            } finally {
                published.value = null
            }
        }
    }

    private object NoAddress : ServerAddressSource {
        override val current: StateFlow<ServerUrl?> = MutableStateFlow(null)

        override fun reportUnreachable(address: ServerUrl) = Unit
    }
}
