package com.skeksik.current.core.network

import com.skeksik.current.core.model.Capabilities
import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServerStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The app's [Capabilities] as they change. Features read this; only server setup and sign-in write it ([MutableCapabilitiesState]). */
interface CapabilitiesState {
    val capabilities: StateFlow<Capabilities>
}

class MutableCapabilitiesState(initial: Capabilities = Capabilities()) : CapabilitiesState {
    private val state = MutableStateFlow(initial)

    override val capabilities: StateFlow<Capabilities> = state.asStateFlow()

    /** Adds a server's status, or replaces it. */
    fun put(status: ServerStatus) = state.update { it.with(status) }

    /**
     * Changes the status of a configured server. Does nothing once [server] is removed, so a check that finishes after its server
     * was removed cannot bring it back.
     */
    fun update(server: ServerRef, change: (ServerStatus) -> ServerStatus) = state.update { current ->
        val status = current.status(server) ?: return@update current
        val changed = change(status)
        require(changed.server == server) { "update must not change the server" }
        current.with(changed)
    }

    fun remove(server: ServerRef) = state.update { it.without(server) }
}
