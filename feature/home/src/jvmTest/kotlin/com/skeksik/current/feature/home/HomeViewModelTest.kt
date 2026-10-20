package com.skeksik.current.feature.home

import com.skeksik.current.core.model.AddressScope
import com.skeksik.current.core.model.SavedServer
import com.skeksik.current.core.model.ServerAddress
import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServerStatus
import com.skeksik.current.core.model.ServiceType
import com.skeksik.current.core.model.User
import com.skeksik.current.core.network.MutableCapabilitiesState
import com.skeksik.current.core.network.ServerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

private val home = ServerRef(ServiceType.jellyfin, "home")
private val cabin = ServerRef(ServiceType.jellyfin, "cabin")

private fun saved(ref: ServerRef, name: String) =
    SavedServer(ref, "id-${ref.key}", name, listOf(ServerAddress("http://media.lan:8096", AddressScope.Local)))

/** Keeps servers in memory and mirrors them into the capabilities state, signed out, as the stored repository does. */
private class FakeServers(private val capabilities: MutableCapabilitiesState) : ServerRepository {
    private val _servers = MutableStateFlow<List<SavedServer>>(emptyList())
    override val servers: StateFlow<List<SavedServer>> = _servers

    override fun save(server: SavedServer) {
        _servers.value = _servers.value.filterNot { it.ref == server.ref } + server
        if (capabilities.capabilities.value.status(server.ref) == null) capabilities.put(ServerStatus(server.ref))
    }

    override fun remove(ref: ServerRef) {
        _servers.value = _servers.value.filterNot { it.ref == ref }
        capabilities.remove(ref)
    }
}

class HomeViewModelTest {
    private val capabilities = MutableCapabilitiesState()
    private val servers = FakeServers(capabilities)

    private fun CoroutineScope.viewModel() = HomeViewModel(servers, capabilities, scope = this)

    private suspend fun HomeViewModel.settled(expected: HomeUiState) = withTimeout(5_000) { state.first { it == expected } }

    private fun signIn(ref: ServerRef, name: String) =
        capabilities.update(ref) { it.copy(signedIn = true, user = User("u-${ref.key}", ref, name)) }

    @Test
    fun withoutServersItOffersToAddOne() = runBlocking<Unit> {
        val viewModel = viewModel()
        assertEquals(HomeUiState.NoServer, viewModel.state.value)
        coroutineContext.cancelChildren()
    }

    @Test
    fun aRestoredSessionIsShownFromTheFirstValue() = runBlocking<Unit> {
        servers.save(saved(home, "Home"))
        signIn(home, "Alex")
        val viewModel = viewModel()
        assertEquals(HomeUiState.SignedIn("Alex", "Home"), viewModel.state.value)
        coroutineContext.cancelChildren()
    }

    @Test
    fun aSavedServerWithoutSessionOffersToSignInToTheFirstServer() = runBlocking<Unit> {
        val viewModel = viewModel()
        servers.save(saved(home, "Home"))
        servers.save(saved(cabin, "Cabin"))
        viewModel.settled(HomeUiState.SignedOut(home, "Home"))
        coroutineContext.cancelChildren()
    }

    @Test
    fun itFollowsSignInAndSignOutOnAnyServer() = runBlocking<Unit> {
        servers.save(saved(home, "Home"))
        servers.save(saved(cabin, "Cabin"))
        val viewModel = viewModel()
        signIn(cabin, "Sam")
        viewModel.settled(HomeUiState.SignedIn("Sam", "Cabin"))
        capabilities.update(cabin) { it.copy(signedIn = false, user = null) }
        viewModel.settled(HomeUiState.SignedOut(home, "Home"))
        servers.remove(home)
        servers.remove(cabin)
        viewModel.settled(HomeUiState.NoServer)
        coroutineContext.cancelChildren()
    }
}
