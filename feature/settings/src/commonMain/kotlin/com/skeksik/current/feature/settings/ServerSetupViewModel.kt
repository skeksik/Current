package com.skeksik.current.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skeksik.current.core.model.AddressScope
import com.skeksik.current.core.model.SavedServer
import com.skeksik.current.core.model.ServerAddress
import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.network.MutableCapabilitiesState
import com.skeksik.current.core.network.ServerIdentity
import com.skeksik.current.core.network.ServerProbe
import com.skeksik.current.core.network.ServerRepository
import com.skeksik.current.core.network.ServerUrl
import com.skeksik.current.core.network.ServiceErrorKind
import com.skeksik.current.core.network.ServiceException
import com.skeksik.current.core.network.candidates
import com.skeksik.current.core.network.newServerRef
import com.skeksik.current.core.network.scope
import com.skeksik.current.core.network.withAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Settings > Servers > Add server, or add an address to a saved server (ADR 0016). */
sealed interface ServerSetupUiState {
    val input: String

    data class Editing(override val input: String, val problem: ServerSetupProblem? = null) : ServerSetupUiState

    data class Checking(override val input: String) : ServerSetupUiState

    /** A server answered at [address]. With [plainHttpOnInternet], the UI warns before the user confirms. */
    data class Found(
        override val input: String,
        val address: String,
        val name: String,
        val version: String?,
        val scope: AddressScope,
        val plainHttpOnInternet: Boolean,
    ) : ServerSetupUiState

    data class Saved(override val input: String, val server: SavedServer) : ServerSetupUiState
}

enum class ServerSetupProblem {
    /** The text is no address. */
    InvalidAddress,

    /** Nothing answered: offline, wrong host or port, or the server is down. */
    Unreachable,

    /** Something answered too slowly. */
    TimedOut,

    /** The address redirects: a missing base path (`/jellyfin`), or http forced to https. Not followed (ADR 0015). */
    Redirected,

    /** Something answered, but not this service. */
    NotThisService,

    /** The server refused the request (an error status, or remote access rules). */
    Refused,

    /** This server, or this address, is already saved. */
    AlreadyAdded,

    /** When adding an address: another server answers there, so it gets no credentials. */
    DifferentServer,

    /** When adding an address: the server was removed meanwhile (on another page or tab). */
    ServerNotSaved,
}

/**
 * Checks what the user typed and saves the server or the address. Every candidate address ([ServerUrl.candidates]) is asked at once;
 * the best one that answers wins, so `https` is preferred whenever it works. With [addTo], the address is added to that saved server,
 * and only if the same server answers there.
 */
class ServerSetupViewModel internal constructor(
    private val probe: ServerProbe,
    private val servers: ServerRepository,
    private val capabilities: MutableCapabilitiesState,
    private val addTo: ServerRef?,
    /** For tests, which have no main dispatcher; otherwise `viewModelScope`. */
    scope: CoroutineScope?,
) : ViewModel() {
    constructor(
        probe: ServerProbe,
        servers: ServerRepository,
        capabilities: MutableCapabilitiesState,
        addTo: ServerRef? = null,
    ) : this(probe, servers, capabilities, addTo, scope = null)

    private val scope = scope ?: viewModelScope

    /** The ref the new server gets, made once so the probe's log lines and the saved server share it. */
    private val ref: ServerRef = addTo ?: newServerRef(probe.service)

    private val _state = MutableStateFlow<ServerSetupUiState>(ServerSetupUiState.Editing(""))
    val state: StateFlow<ServerSetupUiState> = _state.asStateFlow()

    private var check: Job? = null
    private var found: Pair<ServerUrl, ServerIdentity>? = null

    fun onInput(text: String) {
        check?.cancel()
        found = null
        _state.value = ServerSetupUiState.Editing(text)
    }

    fun check() {
        val input = _state.value.input
        val candidates = ServerUrl.candidates(input, probe.defaultPort)
        if (candidates.isEmpty()) {
            _state.value = ServerSetupUiState.Editing(input, ServerSetupProblem.InvalidAddress)
            return
        }
        check?.cancel()
        _state.value = ServerSetupUiState.Checking(input)
        check = scope.launch { _state.value = resultFor(input, firstAnswer(candidates)) }
    }

    /** Saves what [ServerSetupUiState.Found] shows; does nothing in any other state. */
    fun confirm() {
        val shown = _state.value as? ServerSetupUiState.Found ?: return
        val (url, identity) = found ?: return
        val address = ServerAddress(url.toString(), url.scope)
        val server = when (addTo) {
            null -> SavedServer(ref, identity.serverId, identity.name.ifBlank { url.host }, listOf(address))
            else -> {
                val saved = servers.server(addTo) ?: run {
                    _state.value = ServerSetupUiState.Editing(shown.input, ServerSetupProblem.ServerNotSaved)
                    return
                }
                if (saved.addresses.any { it.url == address.url }) saved else saved.withAddress(address)
            }
        }
        servers.save(server)
        capabilities.update(ref) { it.copy(reachable = true, version = identity.version ?: it.version) }
        _state.value = ServerSetupUiState.Saved(shown.input, server)
    }

    private sealed interface Attempt {
        class Answered(val url: ServerUrl, val identity: ServerIdentity) : Attempt

        class Failed(val error: ServiceException) : Attempt
    }

    /** Asks every candidate at once and returns the first, in candidate order, that answered; or every failure. */
    private suspend fun firstAnswer(candidates: List<ServerUrl>): List<Attempt> = coroutineScope {
        val attempts = candidates.map { url -> async { attempt(url) } }
        val results = mutableListOf<Attempt>()
        for (attempt in attempts) {
            val result = attempt.await()
            if (result is Attempt.Answered) {
                attempts.forEach { it.cancel() }
                return@coroutineScope listOf(result)
            }
            results += result
        }
        results
    }

    private suspend fun attempt(url: ServerUrl): Attempt = try {
        Attempt.Answered(url, probe.identify(ref, url))
    } catch (e: ServiceException) {
        Attempt.Failed(e)
    }

    private fun resultFor(input: String, attempts: List<Attempt>): ServerSetupUiState {
        val answered = attempts.singleOrNull() as? Attempt.Answered
            ?: return ServerSetupUiState.Editing(input, problemFor(attempts.filterIsInstance<Attempt.Failed>().map { it.error }))
        val (url, identity) = answered.url to answered.identity
        val problem = when (addTo) {
            null -> ServerSetupProblem.AlreadyAdded.takeIf {
                servers.servers.value.any { it.ref.service == probe.service && it.serverId == identity.serverId }
            }
            else -> {
                val saved = servers.server(addTo)
                when {
                    saved == null -> ServerSetupProblem.ServerNotSaved
                    saved.serverId != identity.serverId -> ServerSetupProblem.DifferentServer
                    saved.addresses.any { it.url == url.toString() } -> ServerSetupProblem.AlreadyAdded
                    else -> null
                }
            }
        }
        if (problem != null) return ServerSetupUiState.Editing(input, problem)
        found = url to identity
        return ServerSetupUiState.Found(
            input = input,
            address = url.toString(),
            name = identity.name.ifBlank { url.host },
            version = identity.version,
            scope = url.scope,
            plainHttpOnInternet = !url.isHttps && url.scope == AddressScope.Internet,
        )
    }

    /** The most telling failure: a redirect or a foreign answer says more than silence. */
    private fun problemFor(errors: List<ServiceException>): ServerSetupProblem {
        fun any(predicate: (ServiceException) -> Boolean) = errors.any(predicate)
        return when {
            any { it.kind == ServiceErrorKind.InvalidResponse && it.status in 300..399 } -> ServerSetupProblem.Redirected
            any { it.kind == ServiceErrorKind.InvalidResponse || it.kind == ServiceErrorKind.NotFound } -> ServerSetupProblem.NotThisService
            any { it.status != null } -> ServerSetupProblem.Refused
            any { it.kind == ServiceErrorKind.Timeout } -> ServerSetupProblem.TimedOut
            else -> ServerSetupProblem.Unreachable
        }
    }
}
