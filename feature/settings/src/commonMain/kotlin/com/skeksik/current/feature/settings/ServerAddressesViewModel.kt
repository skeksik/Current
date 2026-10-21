package com.skeksik.current.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skeksik.current.core.model.AddressScope
import com.skeksik.current.core.model.SavedServer
import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.network.ServerAddressSources
import com.skeksik.current.core.network.ServerRepository
import com.skeksik.current.core.network.ServerUrl
import com.skeksik.current.core.network.canRemoveAddress
import com.skeksik.current.core.network.withAddressMoved
import com.skeksik.current.core.network.withoutAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Settings > Servers > a server's addresses, in the user's priority order (ADR 0019). Adding one is [ServerSetupViewModel] with `addTo`. */
data class ServerAddressesUiState(
    val name: String = "",
    /** Highest priority first. */
    val addresses: List<AddressRow> = emptyList(),
    /** False once the server is no longer saved; the UI leaves the screen. */
    val saved: Boolean = true,
)

data class AddressRow(
    val url: String,
    val scope: AddressScope,
    /** Plain HTTP on an internet address: the UI keeps the warning shown when it was added. */
    val plainHttpOnInternet: Boolean,
    /** The address requests go to now. No row is in use while the server cannot be reached. */
    val inUse: Boolean,
    /** False for the last address: a server keeps at least one. */
    val canRemove: Boolean,
)

class ServerAddressesViewModel internal constructor(
    private val server: ServerRef,
    private val servers: ServerRepository,
    sources: ServerAddressSources,
    /** For tests, which have no main dispatcher; otherwise `viewModelScope`. */
    scope: CoroutineScope?,
) : ViewModel() {
    constructor(server: ServerRef, servers: ServerRepository, sources: ServerAddressSources) : this(server, servers, sources, scope = null)

    private val scope = scope ?: viewModelScope

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<ServerAddressesUiState> = servers.servers
        .map { list -> list.firstOrNull { it.ref == server } }
        .distinctUntilChanged()
        .flatMapLatest { saved ->
            if (saved == null) flowOf(ServerAddressesUiState(saved = false))
            else sources.forServer(saved).current.map { inUse -> stateFor(saved, inUse) }
        }
        .stateIn(this.scope, SharingStarted.Eagerly, servers.server(server)?.let { stateFor(it, null) } ?: ServerAddressesUiState(saved = false))

    /** Removes [url], unless it is the server's last address. */
    fun remove(url: String) = edit { saved -> if (saved.canRemoveAddress(url)) saved.withoutAddress(url) else null }

    /** Moves the address at [from] to [to] in the priority order; the first is tried first. */
    fun move(from: Int, to: Int) = edit { saved ->
        if (from in saved.addresses.indices && to in saved.addresses.indices) saved.withAddressMoved(from, to) else null
    }

    fun moveUp(url: String) = edit { saved -> saved.indexOf(url)?.takeIf { it > 0 }?.let { saved.withAddressMoved(it, it - 1) } }

    fun moveDown(url: String) =
        edit { saved -> saved.indexOf(url)?.takeIf { it < saved.addresses.lastIndex }?.let { saved.withAddressMoved(it, it + 1) } }

    /** Saves what [change] returns; null leaves the server as it is. */
    private fun edit(change: (SavedServer) -> SavedServer?) {
        val saved = servers.server(server) ?: return
        change(saved)?.let(servers::save)
    }

    private fun SavedServer.indexOf(url: String): Int? = addresses.indexOfFirst { it.url == url }.takeIf { it >= 0 }

    private fun stateFor(saved: SavedServer, inUse: ServerUrl?) = ServerAddressesUiState(
        name = saved.name,
        addresses = saved.addresses.map { address ->
            val url = ServerUrl.parse(address.url)
            AddressRow(
                url = address.url,
                scope = address.scope,
                plainHttpOnInternet = url != null && !url.isHttps && address.scope == AddressScope.Internet,
                inUse = url != null && url == inUse,
                canRemove = saved.canRemoveAddress(address.url),
            )
        },
    )
}
