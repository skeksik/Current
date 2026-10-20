package com.skeksik.current.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skeksik.current.core.model.Capabilities
import com.skeksik.current.core.model.SavedServer
import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServiceType
import com.skeksik.current.core.network.CapabilitiesState
import com.skeksik.current.core.network.ServerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Home, where the app starts. Library content comes in M2; until then it says what to do next. */
sealed interface HomeUiState {
    /** No server is saved: offer to add one. */
    data object NoServer : HomeUiState

    /** Servers are saved but none is signed in: offer to sign in to [server], the first saved Jellyfin server (else the first saved one). */
    data class SignedOut(val server: ServerRef, val serverName: String) : HomeUiState

    /** Signed in on [serverName], the first signed-in server, as [userName] (null for services without users). */
    data class SignedIn(val userName: String?, val serverName: String) : HomeUiState
}

class HomeViewModel internal constructor(
    servers: ServerRepository,
    capabilities: CapabilitiesState,
    /** For tests, which have no main dispatcher; otherwise `viewModelScope`. */
    scope: CoroutineScope?,
) : ViewModel() {
    constructor(servers: ServerRepository, capabilities: CapabilitiesState) : this(servers, capabilities, scope = null)

    /** Starts from the current values, so the first frame already shows the restored session rather than a flash of another state. */
    val state: StateFlow<HomeUiState> = combine(servers.servers, capabilities.capabilities, ::homeState)
        .stateIn(scope ?: viewModelScope, SharingStarted.Eagerly, homeState(servers.servers.value, capabilities.capabilities.value))
}

private fun homeState(saved: List<SavedServer>, now: Capabilities): HomeUiState {
    if (saved.isEmpty()) return HomeUiState.NoServer
    for (server in saved) {
        val status = now.status(server.ref) ?: continue
        if (status.signedIn) return HomeUiState.SignedIn(status.user?.name, server.name)
    }
    // Only Jellyfin has a sign-in page yet; the UI offers settings for any other service.
    val first = saved.firstOrNull { it.ref.service == ServiceType.jellyfin } ?: saved.first()
    return HomeUiState.SignedOut(first.ref, first.name)
}
