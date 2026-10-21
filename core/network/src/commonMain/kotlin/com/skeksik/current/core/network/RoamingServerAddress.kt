package com.skeksik.current.core.network

import com.skeksik.current.core.model.AddressScope
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
    /** While it denies local addresses, they are not asked: on Android a denied probe only times out (ADR 0019, ADR 0020). */
    private val localAccess: LocalNetworkAccess = LocalNetworkAccess.NotNeeded,
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

        /** A request found no address. */
        data object Requested : Event

        /** A request to [address], the one in use when it was reported, failed. */
        data class Failed(val address: ServerUrl) : Event

        data class TimerFired(val token: Int, val full: Boolean, val requested: Boolean) : Event

        data class ProbeResult(val check: Int, val index: Int, val outcome: Probed) : Event

        /** [LocalNetworkAccess.changes]: the local network access may have been granted or denied. */
        data object AccessChanged : Event

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
    private val accessQueued = MutableStateFlow(false)

    /** The same for a [Event.Failed]: the address it reports, while one is queued. */
    private val failureQueued = MutableStateFlow<ServerUrl?>(null)
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
        // At most twice the bound in all, however many resumed networks come. Time while suspended does not count, whether or not
        // the process is actually frozen: a request waits for the return without a limit.
        var left = requestWaitsAtMost * 2
        while (true) {
            if (view.value.suspended) {
                view.first { !it.suspended }
                continue
            }
            if (left <= Duration.ZERO) break
            val resumes = view.value.resumes
            val wait = minOf(requestWaitsAtMost, left)
            val started = time.markNow()
            val seen = withTimeoutOrNull(wait) { view.first { it.suspended || (!it.holding && it.changes >= changesSeen.value) } }
            if (seen != null && !seen.suspended) {
                val address = seen.address?.takeIf { it in latest.value.urls() }
                if (address == null) request()
                return address
            }
            left -= if (seen == null) wait else started.elapsedNow()
            // Suspended meanwhile, or a wait that ran out during a freeze and fired at the return before the loop saw it.
            if (view.value.suspended) continue
            // The network was blocked while this request waited: wait again rather than fail, once per resumed network.
            if (view.value.resumes == resumes) break
        }
        request()
        return null
    }

    override fun reportUnreachable(address: ServerUrl) {
        if (address != view.value.address) return
        if (failureQueued.compareAndSet(expect = null, update = address)) events.trySend(Event.Failed(address))
        // Another address's failure is queued (the address in use just changed): this one only asks for a check.
        else if (failureQueued.value != address) request()
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
                // Unlike network changes, this signal may end: a platform that never asks has nothing to report.
                launch {
                    localAccess.changes.collect {
                        if (accessQueued.compareAndSet(expect = false, update = true)) events.send(Event.AccessChanged)
                    }
                }
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

        /** The address in use, which a request found failing: it counts as unproved until it answers in this check. */
        var suspect: ServerUrl? = null
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

        /** The address in use failed a request and no check has asked it since: the next check treats it as unproved. */
        private var failed: ServerUrl? = null

        /** Whether local addresses can be reached, as last read from [localAccess]. */
        private var localAllowed = localAccess.status().allowsLocal

        /**
         * Internet addresses whose probe timed out while local access was denied: most likely a name that resolves to a local address
         * on this network (split DNS), so checks skip them until the network changes or access is granted.
         */
        private val deniedNames = mutableSetOf<ServerUrl>()

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
                is Event.Failed -> {
                    failureQueued.value = null
                    failedRequest(event)
                }
                is Event.TimerFired -> if (event.token == timerToken && check == null && !settlePending) {
                    timerDue = null
                    startCheck(full = event.full, woken = event.requested)
                }
                is Event.ProbeResult -> probed(event)
                Event.AccessChanged -> {
                    accessQueued.value = false
                    accessChanged()
                }
                is Event.CheckExpired -> check?.takeIf { it.id == event.check }?.let { expired ->
                    finish(expired, expired.best?.let(expired.candidates::get))
                }
            }
        }

        private fun edited(edit: SavedServer) {
            val reordered = edit.addresses != server.addresses
            server = edit
            if (!reordered) return
            deniedNames.retainAll(edit.urls().toSet())
            if (address != null && address !in edit.urls()) {
                address = null
                unproved = false
                failed = null
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
            failed = null
            holding = true
            again = false
            retry = timing.retryFirst
            // A name that resolved to a local address on the old network may resolve to a public one on this.
            deniedNames.clear()
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
         * Local network access was granted or denied (only a flip counts; the signal also comes when nothing changed). A check
         * starts at once, replacing one that was asked under the old answer, and the retry waits start over. With no address left
         * in use, requests wait for that check, as after a resumed network.
         */
        private fun accessChanged() {
            if (!followAccess()) return
            retry = timing.retryFirst
            if (address == null) holding = true
            publish()
            if (!settlePending && !suspended) startCheck(full = true, woken = true)
        }

        /**
         * Reads [localAccess] and returns whether it flipped. A grant forgets the names that timed out; a denial drops an address in
         * use that checks now skip, since nothing at it can answer.
         */
        private fun followAccess(): Boolean {
            val allowed = localAccess.status().allowsLocal
            if (allowed == localAllowed) return false
            localAllowed = allowed
            if (allowed) {
                deniedNames.clear()
            } else if (address?.let(::skipped) == true) {
                address = null
                unproved = false
                failed = null
                capabilities.update(server.ref) { it.copy(reachable = null) }
            }
            return true
        }

        /** An address the checks leave out while local access is denied. */
        private fun skipped(url: ServerUrl) = !localAllowed && (url.scope == AddressScope.Local || url in deniedNames)

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

        /**
         * A request to the address in use failed: the check that answers it, the one running or the next, treats that address as
         * unproved, so the first lower address that proves itself replaces it at once instead of when the check ends.
         */
        private fun failedRequest(event: Event.Failed) {
            // The address is no longer in use (a network change, an edit or a check replaced it): nothing to answer. A report sent
            // before the address was proved again can still mark it; that costs at most one check's early move to a lower address.
            if (event.address != address) return
            // One kept across a suspension is unproved already.
            if (!unproved) {
                val running = check
                if (running == null) {
                    failed = event.address
                } else {
                    running.suspect = event.address
                    // A lower address already proved in this check takes over now, not when the failing one's probe ends.
                    running.best?.let(running.candidates::get)?.takeIf { it != address }?.let { useMeanwhile(running, it) }
                }
            }
            requested()
        }

        private fun startCheck(full: Boolean, woken: Boolean) {
            // A failure the replaced check had not answered yet carries over to this one.
            failed = (failed ?: check?.suspect)?.takeIf { it == address }
            cancelCheck()
            cancelTimer()
            // The return from the background starts the next one.
            if (suspended) return
            // Cheap, and a grant may have come without a signal.
            if (followAccess()) publish()
            val urls = server.urls()
            val inUse = address?.let(urls::indexOf) ?: -1
            val suspect = failed
            failed = null
            // The periodic check asks the address in use and those above it; all of them only when none of those answers, or when
            // the one in use failed a request. Addresses that cannot answer while local access is denied are never asked, so a check
            // left with none ends at once.
            val partial = !full && inUse >= 0 && suspect == null
            val candidates = (if (partial) urls.subList(0, inUse + 1) else urls).filterNot(::skipped)
            val started = Check(++checks, candidates, full = !partial, woken = woken)
            started.suspect = suspect
            val asked = server
            started.job = scope.launch {
                // A probe that never posts (cancelled inside, or an engine that ignores cancellation) must not hold the check forever.
                launch {
                    delay(probe.timeout * 2)
                    events.send(Event.CheckExpired(started.id))
                }
                candidates.forEachIndexed { index, url ->
                    launch { events.send(Event.ProbeResult(started.id, index, ask(asked, url))) }
                }
            }
            check = started
            if (candidates.isEmpty()) finish(started, null)
        }

        private fun probed(result: Event.ProbeResult) {
            val running = check?.takeIf { it.id == result.check } ?: return
            running.done[result.index] = true
            val url = running.candidates[result.index]
            val proved = result.outcome == Probed.Proved
            // Only an internet name can get here while denied; one that answered nothing in time most likely resolves locally.
            if (result.outcome == Probed.TimedOut && !localAllowed) deniedNames += url
            val before = running.best
            if (proved && (before == null || result.index < before)) running.best = result.index
            if (proved && url == running.suspect) running.suspect = null
            val best = running.best
            when {
                best != null && (0 until best).all { running.done[it] } -> finish(running, running.candidates[best])
                proved && result.index == best -> useMeanwhile(running, running.candidates[best])
                running.done.all { it } -> finish(running, null)
            }
        }

        /**
         * An address proved while higher ones are still being asked: used when none is in use, or when it ranks above the one in use,
         * so a healthy address is never swapped for a lower one mid-check. One kept across a suspension is not proved, and neither is
         * one that failed a request and has not answered in this check, so those count as none.
         */
        private fun useMeanwhile(running: Check, proved: ServerUrl) {
            val urls = server.urls()
            val inUse = address?.takeUnless { unproved || it == running.suspect }?.let(urls::indexOf)?.takeIf { it >= 0 }
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
                // A failure reported during the partial check still applies to the full one.
                failed = done.suspect
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

    private suspend fun ask(server: SavedServer, url: ServerUrl): Probed {
        val asked = time.markNow()
        return try {
            if (probe.identify(server.ref, url).serverId == server.serverId) Probed.Proved else Probed.Failed
        } catch (e: ServiceException) {
            // On Android the connect timeout is lowered to the request timeout, so a connection that never opens may also end as
            // Unreachable at that moment; what matters is that nothing answered for the probe's whole timeout.
            if (e.kind == ServiceErrorKind.Timeout || asked.elapsedNow() >= probe.timeout) Probed.TimedOut else Probed.Failed
        }
    }

    /** What one probe found: the saved server, anything else, or no answer within its timeout. */
    private enum class Probed { Proved, Failed, TimedOut }
}

/**
 * For a service that cannot be identified without credentials (Stash, later): its addresses in priority order, moving to the next on a
 * reported failure and back to the first on a network change. Every saved address is one the user typed or confirmed (ADR 0016).
 * While [localAccess] denies local addresses, only the internet ones take turns, unless every address is local.
 */
internal class OrderedServerAddress(
    server: SavedServer,
    private val localAccess: LocalNetworkAccess = LocalNetworkAccess.NotNeeded,
) : ManagedServerAddress {
    /** [allowed]: whether local addresses could be reached when [address] was chosen. */
    private data class Ordered(val addresses: List<ServerUrl>, val address: ServerUrl?, val allowed: Boolean)

    private val state = MutableStateFlow(ordered(server.urls()))
    private val published = MutableStateFlow(state.value.address)

    override val current: StateFlow<ServerUrl?> = published.asStateFlow()

    override suspend fun awaitCurrent(): ServerUrl? = state.value.address

    override fun reportUnreachable(address: ServerUrl) = state.update { ordered ->
        if (ordered.address != address) return@update ordered
        val usable = usable(ordered.addresses, ordered.allowed)
        ordered.copy(address = usable.getOrNull((usable.indexOf(address) + 1) % usable.size))
    }

    override fun update(server: SavedServer) = state.update { ordered ->
        if (server.urls() == ordered.addresses) ordered else ordered(server.urls())
    }

    override suspend fun run(networkChanges: Flow<NetworkChange>) {
        try {
            coroutineScope {
                launch { state.collect { published.value = it.address } }
                launch {
                    localAccess.changes.collect {
                        val allowed = localAccess.status().allowsLocal
                        state.update { if (it.allowed == allowed) it else ordered(it.addresses, allowed) }
                    }
                }
                networkChanges.collect { change ->
                    if (change == NetworkChange.Changed) state.update { ordered(it.addresses) }
                }
                error("Network changes ended")
            }
        } finally {
            state.update { it.copy(addresses = emptyList(), address = null) }
            published.value = null
        }
    }

    private fun ordered(addresses: List<ServerUrl>, allowed: Boolean = localAccess.status().allowsLocal) =
        Ordered(addresses, usable(addresses, allowed).firstOrNull(), allowed)

    /** While local access is denied, the internet addresses; all of them when every one is local, as before access was asked for. */
    private fun usable(addresses: List<ServerUrl>, allowed: Boolean) =
        if (allowed) addresses else addresses.filter { it.scope != AddressScope.Local }.ifEmpty { addresses }
}

private fun SavedServer.urls(): List<ServerUrl> = addresses.mapNotNull { ServerUrl.parse(it.url) }
