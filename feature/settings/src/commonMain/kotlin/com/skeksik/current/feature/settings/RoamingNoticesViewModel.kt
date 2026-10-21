package com.skeksik.current.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skeksik.current.core.model.AddressScope
import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.network.ServerAddressSources
import com.skeksik.current.core.network.ServerRepository
import com.skeksik.current.core.network.ServerUrl
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/** Roaming switched [server] to another address, on the network kind [scope] (ADR 0019). It never carries the address. */
data class RoamingNotice(val server: ServerRef, val serverName: String, val scope: AddressScope)

/**
 * A brief notice in the shell when roaming switches a saved server to another address (`ServerAddressSource.switches`). Only roaming
 * counts: the address that changes because the user edited the addresses is not a switch, and neither is the first one after start
 * (ADR 0019). The notice clears itself after [shownFor]; a newer switch replaces it.
 */
class RoamingNoticesViewModel internal constructor(
    private val servers: ServerRepository,
    sources: ServerAddressSources,
    /** For tests, which have no main dispatcher; otherwise `viewModelScope`. */
    scope: CoroutineScope?,
    private val shownFor: Duration = 4.seconds,
) : ViewModel() {
    constructor(servers: ServerRepository, sources: ServerAddressSources) : this(servers, sources, scope = null)

    private val scope = scope ?: viewModelScope
    private val shown = MutableStateFlow<RoamingNotice?>(null)
    private var clearing: Job? = null

    /** The notice to show now; null when there is none. */
    val state: StateFlow<RoamingNotice?> = shown.asStateFlow()

    init {
        @OptIn(ExperimentalCoroutinesApi::class)
        this.scope.launch {
            servers.servers
                .map { list -> list.map { it.ref } }
                .distinctUntilChanged()
                .flatMapLatest { refs ->
                    val switches = refs.mapNotNull { ref ->
                        servers.server(ref)?.let { saved -> sources.forServer(saved).switches.map { ref to it } }
                    }
                    if (switches.isEmpty()) emptyFlow() else merge(*switches.toTypedArray())
                }
                .collect { (ref, url) -> show(ref, url) }
        }
    }

    /** The user closed the notice. */
    fun dismiss() {
        clearing?.cancel()
        shown.value = null
    }

    private fun show(ref: ServerRef, url: ServerUrl) {
        val saved = servers.server(ref) ?: return
        val address = saved.addresses.firstOrNull { ServerUrl.parse(it.url) == url } ?: return
        shown.value = RoamingNotice(ref, saved.name, address.scope)
        clearing?.cancel()
        clearing = scope.launch {
            delay(shownFor)
            shown.value = null
        }
    }
}
