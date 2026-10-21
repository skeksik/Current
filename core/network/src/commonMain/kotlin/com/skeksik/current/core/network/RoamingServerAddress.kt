package com.skeksik.current.core.network

import com.skeksik.current.core.model.SavedServer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** When a [RoamingServerAddress] asks its addresses again, besides network changes and failed requests (ADR 0019). */
data class RoamingTiming(
    /** How often the address in use and the ones above it are asked: to move back to a local address, or notice a dead one. */
    val recheckEvery: Duration = 5.minutes,
    /** While no address answers: the first wait before asking again, doubled after each miss up to [retryAtMost]. */
    val retryFirst: Duration = 15.seconds,
    val retryAtMost: Duration = 5.minutes,
    /**
     * After a network change, how long no further change must come before the check starts. Platforms report one transition as a
     * burst of updates (docs/platform-notes.md, "Apple path monitor and roaming on a Mac"); requests wait from the first one.
     */
    val networkSettles: Duration = 1.seconds,
    /**
     * How long a request waits for a check in progress (at start, after a network change). Null: [networkSettles] plus twice the
     * probe's timeout, room for a check that a network change restarts.
     */
    val requestWaitsAtMost: Duration? = null,
)

/** A [ServerAddressSource] that [RoamingAddressSources] keeps running for one saved server. */
internal interface ManagedServerAddress : ServerAddressSource {
    /** The saved server changed: addresses added, removed or reordered. */
    fun update(server: SavedServer)

    /**
     * Chooses addresses until cancelled; afterwards the source has no address. Runs once: a run that ends leaves its queue and state
     * behind, so a source is replaced, not run again ([RoamingAddressSources]).
     */
    suspend fun run(networkChanges: Flow<NetworkChange>)
}

/**
 * Uses the best address, in the user's priority order, at which the saved server answers (ADR 0019). Every address is asked at once
 * through [probe], without credentials, and only one that reports the saved server id is ever used.
 *
 * One loop handles every input in order from a single queue: edits, network changes and their settle, a resumed network, requests,
 * timers and probe results. Only the loop changes the state; requests read what it publishes. Timers and probes post events tagged
 * with what they belong to, and the loop ignores one whose settle, timer or check has been replaced.
 */
internal class RoamingServerAddress(
    server: SavedServer,
    private val probe: ServerProbe,
    private val capabilities: MutableCapabilitiesState,
    private val timing: RoamingTiming = RoamingTiming(),
    /** Measures the request window and timers' due times; tests pass their virtual clock. */
    private val time: TimeSource = TimeSource.Monotonic,
) : ManagedServerAddress {
    /**
     * What requests see. [holding]: requests wait (start, network change). [resumes] counts resumed networks, so a wait can restart.
     * [changes] counts the network changes and suspensions the loop has handled. [suspended]: the app is in the background.
     */
    private data class View(
        val address: ServerUrl?,
        val holding: Boolean,
        val resumes: Int,
        val changes: Int,
        val suspended: Boolean = false,
    )

    private sealed interface Event {
        data class Edited(val server: SavedServer) : Event

        data object Changed : Event

        data object Resumed : Event

        data object Suspended : Event

        data class Settled(val token: Int) : Event

        /** A request found no address, or the address in use failed. */
        data object Requested : Event

        data class TimerFired(val token: Int, val full: Boolean, val requested: Boolean) : Event

        data class ProbeResult(val check: Int, val index: Int, val proved: Boolean) : Event

        /** A check ran past its deadline: some probe never answered, so it ends with what it has. */
        data class CheckExpired(val check: Int) : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)

    /**
     * Whether a [Event.Requested] or [Event.Resumed] is queued and not yet taken: those come at the rate of requests or of
     * platform signals, and one queued says all that more would, so the queue stays bounded.
     */
    private val requestQueued = MutableStateFlow(false)
    private val resumeQueued = MutableStateFlow(false)
    private val view = MutableStateFlow(View(address = null, holding = true, resumes = 0, changes = 0))

    /**
     * The network changes and suspensions reported so far, counted as they arrive: requests hold until the loop has handled each one.
     */
    private val changesSeen = MutableStateFlow(0)
    private val published = MutableStateFlow<ServerUrl?>(null)

    /** The address list as last edited, written at once: a removed address gets nothing, not even before the loop has run. */
    private val latest = MutableStateFlow(server)

    override val current: StateFlow<ServerUrl?> = published.asStateFlow()

    private val requestWaitsAtMost = timing.requestWaitsAtMost ?: (timing.networkSettles + probe.timeout * 2)

    override suspend fun awaitCurrent(): ServerUrl? {
        // At most twice the bound in all, however many resumed networks come; time spent suspended does not count.
        var left = requestWaitsAtMost * 2
        while (true) {
            val resumes = view.value.resumes
            val wait = minOf(requestWaitsAtMost, left)
            val seen = withTimeoutOrNull(wait) { view.first { !it.holding && it.changes >= changesSeen.value } }
            if (seen != null) {
                val address = seen.address?.takeIf { it in latest.value.urls() }
                if (address == null) request()
                return address
            }
            left -= wait
            // A wait that ran out while the app was suspended fires on its return, before the loop has seen it: give the loop a bound
            // to handle the return.
            if (view.value.suspended) withTimeoutOrNull(requestWaitsAtMost) { view.first { !it.suspended } }
            // The app was suspended, or the network was blocked, while this request waited: wait again rather than fail.
            // Once per resumed network, and only while time is left.
            if (view.value.resumes == resumes || view.value.suspended || left <= Duration.ZERO) {
                request()
                return null
            }
        }
    }

    override fun reportUnreachable(address: ServerUrl) {
        if (address == view.value.address) request()
    }

    private fun request() {
        if (requestQueued.compareAndSet(expect = false, update = true)) events.trySend(Event.Requested)
    }

    override fun update(server: SavedServer) {
        latest.value = server
        events.trySend(Event.Edited(server))
    }

    override suspend fun run(networkChanges: Flow<NetworkChange>) {
        try {
            coroutineScope {
                launch { view.collect { published.value = it.address } }
                launch {
                    networkChanges.collect { change ->
                        when (change) {
                            NetworkChange.Changed -> {
                                changesSeen.update { it + 1 }
                                events.send(Event.Changed)
                            }
                            NetworkChange.Resumed ->
                                if (resumeQueued.compareAndSet(expect = false, update = true)) events.send(Event.Resumed)
                            NetworkChange.Suspended -> {
                                changesSeen.update { it + 1 }
                                events.send(Event.Suspended)
                            }
                        }
                    }
                    // A source that no longer hears of network changes must not go on blind.
                    error("Network changes ended")
                }
                try {
                    Loop(this).run()
                } finally {
                    // At once, not once every probe has returned: a dying run's address is not used meanwhile.
                    stop()
                }
            }
        } finally {
            stop()
        }
    }

    /** No request waits on a stopped source, not even for a change the loop never handled. */
    private fun stop() {
        view.update { it.copy(address = null, holding = false, changes = Int.MAX_VALUE, suspended = false) }
        published.value = null
    }

    private inner class Check(val id: Int, val candidates: List<ServerUrl>, val full: Boolean, val woken: Boolean) {
        lateinit var job: Job
        val done = BooleanArray(candidates.size)
        var best: Int? = null
    }

    /** The state, owned by [run]; nothing else touches it. */
    private inner class Loop(private val scope: CoroutineScope) {
        private var server = latest.value
        private var address: ServerUrl? = null
        private var holding = true
        private var resumes = view.value.resumes
        private var changes = view.value.changes
        private var settlePending = false
        private var settleToken = 0
        private var settleJob: Job? = null
        private var timerToken = 0
        private var timerJob: Job? = null
        private var timerDue: TimeMark? = null
        private var checks = 0
        private var check: Check? = null

        /** Something asked for a check while one ran (a resumed network): one more follows it. */
        private var again = false
        private var retry = timing.retryFirst
        private var lastCheckEnded: TimeMark? = null

        /** The app is in the background: no check runs, and requests wait for the return. */
        private var suspended = false

        /** The address in use was kept across a suspension and is not proved since: requests wait, and any proved address replaces it. */
        private var unproved = false

        suspend fun run(): Nothing {
            startCheck(full = true, woken = false)
            for (event in events) handle(event)
            error("The event queue is never closed")
        }

        private fun handle(event: Event) {
            when (event) {
                is Event.Edited -> edited(event.server)
                Event.Changed -> changed()
                Event.Suspended -> suspended()
                Event.Resumed -> {
                    resumeQueued.value = false
                    resumed()
                }
                is Event.Settled -> if (event.token == settleToken && settlePending) {
                    settlePending = false
                    startCheck(full = true, woken = true)
                }
                Event.Requested -> {
                    requestQueued.value = false
                    requested()
                }
                is Event.TimerFired -> if (event.token == timerToken && check == null && !settlePending) {
                    timerDue = null
                    startCheck(full = event.full, woken = event.requested)
                }
                is Event.ProbeResult -> probed(event)
                is Event.CheckExpired -> check?.takeIf { it.id == event.check }?.let { expired ->
                    finish(expired, expired.best?.let(expired.candidates::get))
                }
            }
        }

        private fun edited(edit: SavedServer) {
            val reordered = edit.addresses != server.addresses
            server = edit
            if (!reordered) return
            if (address != null && address !in edit.urls()) {
                address = null
                unproved = false
            }
            publish()
            // A pending settle starts the next check; otherwise the edit does, at once.
            if (!settlePending) startCheck(full = true, woken = true)
        }

        /** The old address may now be another server's: drop it, hold requests, and check once updates have settled. */
        private fun changed() {
            changes++
            address = null
            unproved = false
            holding = true
            again = false
            retry = timing.retryFirst
            cancelCheck()
            cancelTimer()
            settlePending = true
            settleJob?.cancel()
            val token = ++settleToken
            settleJob = scope.launch {
                delay(timing.networkSettles)
                events.send(Event.Settled(token))
            }
            publish()
        }

        /**
         * The app is going to the background. Nothing runs while it is suspended, and the network may change meanwhile, so requests
         * wait from now until a check after the return proves an address; the address in use is kept as the first to try.
         */
        private fun suspended() {
            changes++
            suspended = true
            holding = true
            unproved = address != null
            again = false
            cancelCheck()
            cancelTimer()
            settlePending = false
            settleJob?.cancel()
            publish()
        }

        /**
         * The network is usable again: ask again at once, keep the address, and start the retry waits over. With no address in use,
         * requests wait for that check instead of failing at once: a switch made while blocked left none, and the check usually proves
         * one within a fraction of a second.
         */
        private fun resumed() {
            resumes++
            retry = timing.retryFirst
            if (suspended) {
                // Back from the background: whatever happened meanwhile, check at once; requests wait for it.
                suspended = false
                publish()
                startCheck(full = true, woken = true)
                return
            }
            if (address == null) holding = true
            publish()
            when {
                settlePending -> Unit
                check != null -> again = true
                else -> startCheck(full = true, woken = true)
            }
        }

        /**
         * A request asks for a check: never while one runs or a settle is pending (those answer it), and at most once per
         * [RoamingTiming.retryFirst] after the last check ended. One that comes sooner gets a check when that window ends.
         */
        private fun requested() {
            if (check != null || settlePending || suspended) return
            val ended = lastCheckEnded ?: return
            val left = timing.retryFirst - ended.elapsedNow()
            if (left <= Duration.ZERO) startCheck(full = true, woken = true)
            else schedule(left, full = true, requested = true, onlyIfSooner = true)
        }

        private fun startCheck(full: Boolean, woken: Boolean) {
            cancelCheck()
            cancelTimer()
            // The return from the background starts the next one.
            if (suspended) return
            val urls = server.urls()
            val inUse = address?.let(urls::indexOf) ?: -1
            // The periodic check asks the address in use and those above it; all of them only when none of those answers.
            val partial = !full && inUse >= 0
            val candidates = if (partial) urls.subList(0, inUse + 1) else urls
            val started = Check(++checks, candidates, full = !partial, woken = woken)
            val asked = server
            started.job = scope.launch {
                // A probe that never posts (cancelled inside, or an engine that ignores cancellation) must not hold the check forever.
                launch {
                    delay(probe.timeout * 2)
                    events.send(Event.CheckExpired(started.id))
                }
                candidates.forEachIndexed { index, url ->
                    launch { events.send(Event.ProbeResult(started.id, index, isSavedServer(asked, url))) }
                }
            }
            check = started
            if (candidates.isEmpty()) finish(started, null)
        }

        private fun probed(result: Event.ProbeResult) {
            val running = check?.takeIf { it.id == result.check } ?: return
            running.done[result.index] = true
            val before = running.best
            if (result.proved && (before == null || result.index < before)) running.best = result.index
            val best = running.best
            when {
                best != null && (0 until best).all { running.done[it] } -> finish(running, running.candidates[best])
                result.proved && result.index == best -> useMeanwhile(running.candidates[best])
                running.done.all { it } -> finish(running, null)
            }
        }

        /**
         * An address proved while higher ones are still being asked: used when none is in use, or when it ranks above the one in use,
         * so a healthy address is never swapped for a lower one mid-check. One kept across a suspension is not proved, so it counts as
         * none.
         */
        private fun useMeanwhile(proved: ServerUrl) {
            val urls = server.urls()
            val inUse = address?.takeUnless { unproved }?.let(urls::indexOf)?.takeIf { it >= 0 }
            if (inUse != null && urls.indexOf(proved) >= inUse) return
            address = proved
            unproved = false
            holding = false
            publish()
            capabilities.update(server.ref) { it.copy(reachable = true) }
        }

        private fun finish(done: Check, found: ServerUrl?) {
            check = null
            done.job.cancel()
            lastCheckEnded = time.markNow()
            // A miss keeps the last proved address: only a network change, or an edit that removes it, clears it. One kept across a
            // suspension and not proved since is cleared too, since the network may have changed meanwhile.
            if (found != null) address = found else if (unproved) address = null
            unproved = false
            // A resumed network that came during this check, with nothing proved, keeps requests waiting for the check it asked for.
            holding = again && address == null
            publish()
            if (found == null && !done.full) {
                startCheck(full = true, woken = done.woken)
                return
            }
            capabilities.update(server.ref) { it.copy(reachable = found != null) }
            if (again) {
                again = false
                startCheck(full = true, woken = true)
                return
            }
            if (found == null) {
                // Only a miss on the timer makes the next wait longer; one that something woke does not.
                val wait = retry
                if (!done.woken) retry = (retry * 2).coerceAtMost(timing.retryAtMost)
                schedule(wait, full = true, requested = false)
            } else {
                retry = timing.retryFirst
                schedule(timing.recheckEvery, full = false, requested = false)
            }
        }

        private fun schedule(after: Duration, full: Boolean, requested: Boolean, onlyIfSooner: Boolean = false) {
            val due = timerDue
            if (onlyIfSooner && due != null && -due.elapsedNow() <= after) return
            cancelTimer()
            val token = ++timerToken
            timerDue = time.markNow() + after
            timerJob = scope.launch {
                delay(after)
                events.send(Event.TimerFired(token, full, requested))
            }
        }

        private fun cancelTimer() {
            timerToken++
            timerJob?.cancel()
            timerJob = null
            timerDue = null
        }

        private fun cancelCheck() {
            check?.job?.cancel()
            check = null
        }

        private fun publish() {
            view.value = View(address, holding, resumes, changes, suspended)
        }
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

    override suspend fun run(networkChanges: Flow<NetworkChange>) {
        try {
            coroutineScope {
                launch { state.collect { published.value = it.address } }
                networkChanges.collect { change ->
                    if (change == NetworkChange.Changed) state.update { it.copy(address = it.addresses.firstOrNull()) }
                }
                error("Network changes ended")
            }
        } finally {
            state.update { it.copy(addresses = emptyList(), address = null) }
            published.value = null
        }
    }
}

private fun SavedServer.urls(): List<ServerUrl> = addresses.mapNotNull { ServerUrl.parse(it.url) }
