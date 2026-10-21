package com.skeksik.current.core.network

import com.skeksik.current.core.model.SavedServer
import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServiceType
import kotlin.math.pow
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
import kotlinx.coroutines.flow.retryWhen
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
 * which cannot prove who it is without credentials, gets an [OrderedServerAddress]. Any other saved server gets no address, so requests
 * to it fail as unreachable and it is reported unreachable; one that is not saved gets no address either.
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
    /** Local addresses are left out while it denies them (ADR 0019). */
    private val localAccess: LocalNetworkAccess = LocalNetworkAccess.NotNeeded,
    /** Where bugs that restart a source are logged. */
    private val log: NetworkLogger = NetworkLogger.None,
) : ServerAddressSources {
    /** One run of [source]; [wait] is how long it waited after the last run that a bug ended. */
    private class Running(val source: StableAddress, val job: Job, val wait: Duration) {
        var started: TimeMark? = null
    }

    private val probes = probes.associateBy { it.service }
    private val changes = networkChanges.changes()
        // A platform signal that fails starts again, so sources never go blind; it may have missed a change meanwhile.
        .retryWhen { cause, attempt ->
            if (cause is CancellationException) return@retryWhen false
            log.bug("Network changes failed", cause)
            delay((timing.retryFirst * 2.0.pow(attempt.coerceAtMost(10L).toInt())).coerceAtMost(timing.retryAtMost))
            emit(NetworkChange.Changed)
            true
        }
        .shareIn(scope, SharingStarted.WhileSubscribed())
    private val running = MutableStateFlow<Map<ServerRef, Running>>(emptyMap())

    init {
        scope.launch { servers.servers.collect { sync() } }
    }

    override fun forServer(server: SavedServer): ServerAddressSource =
        running.value[server.ref]?.source ?: servers.server(server.ref)?.let(::start) ?: NoAddress

    private fun make(server: SavedServer): ManagedServerAddress? = when {
        server.ref.service in probes ->
            RoamingServerAddress(server, probes.getValue(server.ref.service), capabilities, timing, time, localAccess)
        server.ref.service in unidentifiable -> OrderedServerAddress(server, capabilities, localAccess)
        else -> null
    }

    private fun start(server: SavedServer): ServerAddressSource {
        val managed = make(server)
        if (managed == null) {
            // Every request to it fails as unreachable, so it shows as offline rather than unknown.
            capabilities.update(server.ref) { it.copy(reachable = false) }
            return NoAddress
        }
        val source = StableAddress(managed)
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
            try {
                restartIfABugEnded(ref, run, cause)
            } catch (e: Throwable) {
                // Never hand out a source that will not run again: the next caller starts a new one.
                val left = running.value[ref]?.takeIf { it.source === run.source }
                running.update { map -> if (left != null && map[ref] === left) map - ref else map }
                left?.job?.cancel()
                throw e
            }
        }
    }

    private fun restartIfABugEnded(ref: ServerRef, run: Running, cause: Throwable?) {
        val stopped = cause == null || running.value[ref] !== run || scope.coroutineContext[Job]?.isActive == false
        val fresh = if (stopped) null else servers.server(ref)?.let(::make)
        if (fresh == null) {
            running.update { map -> if (map[ref] === run) map - ref else map }
            return
        }
        // The scope's handler never sees a cancellation, so it is logged here.
        if (cause is CancellationException) log.bug("Address source ended by a stray cancellation", cause)
        // The dead source may still hold its run's queued events: requests now wait on the fresh one, which starts cold.
        run.source.replace(fresh)
        capabilities.update(ref) { it.copy(reachable = null) }
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

        override fun reportReachable(address: ServerUrl) = inner.value.reportReachable(address)

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
