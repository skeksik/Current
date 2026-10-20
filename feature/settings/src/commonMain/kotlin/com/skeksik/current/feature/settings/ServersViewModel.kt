package com.skeksik.current.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skeksik.current.core.database.SecretStorage
import com.skeksik.current.core.database.SecretStore
import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServiceType
import com.skeksik.current.core.network.CapabilitiesState
import com.skeksik.current.core.network.ServerRepository
import com.skeksik.current.data.jellyfin.JellyfinAccounts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Settings > Servers: the saved servers, who is signed in where, and where tokens are kept (ADR 0017, ADR 0018). */
data class ServersUiState(
    val servers: List<ServerRow> = emptyList(),
    val secretStorage: SecretStorage = SecretStorage.Memory,
    /** Tokens are only in memory but a file only this user can read is possible; the UI may offer [ServersViewModel.keepSecretsInFile]. */
    val fileOffered: Boolean = false,
)

data class ServerRow(
    val ref: ServerRef,
    val name: String,
    /** The first address in priority order. */
    val address: String,
    /** The signed-in user's name, or null when signed out. */
    val signedInAs: String?,
    /** Null until checked; false while the server cannot be reached. */
    val reachable: Boolean?,
)

class ServersViewModel internal constructor(
    private val servers: ServerRepository,
    capabilities: CapabilitiesState,
    private val accounts: JellyfinAccounts,
    private val secrets: SecretStore,
    /** For tests, which have no main dispatcher; otherwise `viewModelScope`. */
    scope: CoroutineScope?,
) : ViewModel() {
    constructor(servers: ServerRepository, capabilities: CapabilitiesState, accounts: JellyfinAccounts, secrets: SecretStore) :
        this(servers, capabilities, accounts, secrets, scope = null)

    private val scope = scope ?: viewModelScope

    val state: StateFlow<ServersUiState> = combine(servers.servers, capabilities.capabilities, secrets.storage) { saved, now, storage ->
        ServersUiState(
            servers = saved.map { server ->
                val status = now.status(server.ref)
                ServerRow(
                    ref = server.ref,
                    name = server.name,
                    address = server.addresses.first().url,
                    signedInAs = status?.user?.name?.takeIf { status.signedIn },
                    reachable = status?.reachable,
                )
            },
            secretStorage = storage,
            fileOffered = secrets.fileOffered,
        )
    }.stateIn(this.scope, SharingStarted.Eagerly, ServersUiState())

    fun signOut(server: ServerRef) {
        scope.launch { signOutNow(server) }
    }

    /** Signs out first, so the server forgets the session and the keyring forgets the token, then forgets the server. */
    fun remove(server: ServerRef) {
        scope.launch {
            signOutNow(server)
            servers.remove(server)
        }
    }

    fun keepSecretsInFile() {
        scope.launch { secrets.keepInFile() }
    }

    private suspend fun signOutNow(server: ServerRef) {
        if (server.service == ServiceType.jellyfin) accounts.signOut(server)
    }
}
