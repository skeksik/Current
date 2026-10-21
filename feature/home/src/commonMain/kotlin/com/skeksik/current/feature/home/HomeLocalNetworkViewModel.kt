package com.skeksik.current.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skeksik.current.core.model.AddressScope
import com.skeksik.current.core.model.SavedServer
import com.skeksik.current.core.network.LocalNetworkAccess
import com.skeksik.current.core.network.LocalNetworkAskReason
import com.skeksik.current.core.network.LocalNetworkPermission
import com.skeksik.current.core.network.ServerRepository
import com.skeksik.current.core.network.shouldAskForLocalNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What Home shows about local network access (ADR 0020). Only a saved server with a local address ever shows anything. */
sealed interface HomeLocalNetworkState {
    /** Nothing to ask: granted, the platform does not ask, or every saved server is on the internet. */
    data object Hidden : HomeLocalNetworkState

    /**
     * Explain, then ask. With [denied], the user said no, here or on another screen ([LocalNetworkAccess.deniedBefore]): the local
     * servers cannot be reached. The dialog then shows only when the user presses "Ask again".
     */
    data class Ask(val denied: Boolean = false) : HomeLocalNetworkState

    /** The system dialog is up. */
    data object Asking : HomeLocalNetworkState

    /** Denied for good: only the app's settings page can allow it ("Try again" still asks, in case Android would show the dialog). */
    data object Blocked : HomeLocalNetworkState
}

/**
 * Asks for local network access on Home when a saved server has a local address (ADR 0020, [LocalNetworkAskReason.SavedServers]): a
 * denied request to a local address only times out. Never for a user whose servers are all on the internet. The system dialog speaks
 * of "nearby devices", so the page explains first and asks only when the user continues.
 */
class HomeLocalNetworkViewModel internal constructor(
    private val servers: ServerRepository,
    private val access: LocalNetworkAccess,
    /** For tests, which have no main dispatcher; otherwise `viewModelScope`. */
    scope: CoroutineScope?,
) : ViewModel() {
    constructor(servers: ServerRepository, access: LocalNetworkAccess) : this(servers, access, scope = null)

    private val scope = scope ?: viewModelScope

    private val _state = MutableStateFlow(stateFor(servers.servers.value, access.status()))
    val state: StateFlow<HomeLocalNetworkState> = _state.asStateFlow()

    init {
        // A server added or removed later changes whether there is anything to ask.
        this.scope.launch { servers.servers.collect { refresh() } }
    }

    /** Reads the permission again, for instance when the user comes back from the settings page. */
    fun refresh() {
        if (_state.value == HomeLocalNetworkState.Asking) return
        _state.value = stateFor(servers.servers.value, access.status())
    }

    /** Shows the system dialog. */
    fun allow() {
        if (_state.value == HomeLocalNetworkState.Asking) return
        _state.value = HomeLocalNetworkState.Asking
        scope.launch {
            _state.value = stateFor(servers.servers.value, access.request())
        }
    }

    fun openSettings() = access.openSettings()

    private fun stateFor(saved: List<SavedServer>, status: LocalNetworkPermission): HomeLocalNetworkState = when {
        shouldAskForLocalNetwork(LocalNetworkAskReason.SavedServers, saved, status) -> HomeLocalNetworkState.Ask(access.deniedBefore())
        status == LocalNetworkPermission.Blocked && saved.hasLocalAddress() -> HomeLocalNetworkState.Blocked
        else -> HomeLocalNetworkState.Hidden
    }
}

private fun List<SavedServer>.hasLocalAddress() = any { server -> server.addresses.any { it.scope == AddressScope.Local } }
