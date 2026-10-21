package com.skeksik.current.feature.home

import com.skeksik.current.core.model.AddressScope
import com.skeksik.current.core.model.SavedServer
import com.skeksik.current.core.model.ServerAddress
import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServiceType
import com.skeksik.current.core.network.LocalNetworkAccess
import com.skeksik.current.core.network.LocalNetworkPermission
import com.skeksik.current.core.network.ServerRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

private fun server(key: String, vararg scopes: AddressScope) = SavedServer(
    ServerRef(ServiceType.jellyfin, key),
    "id-$key",
    key,
    scopes.mapIndexed { index, scope -> ServerAddress("http://$key-$index.example:8096", scope) },
)

private class Servers(vararg saved: SavedServer) : ServerRepository {
    private val _servers = MutableStateFlow(saved.toList())
    override val servers: StateFlow<List<SavedServer>> = _servers

    override fun save(server: SavedServer) {
        _servers.value = _servers.value.filterNot { it.ref == server.ref } + server
    }

    override fun remove(ref: ServerRef) {
        _servers.value = _servers.value.filterNot { it.ref == ref }
    }
}

/**
 * [answers] are what each request returns, in turn; [status] is what the settings page would show. [denied] is the stored denial, set
 * by an answer that is not a grant (or by another screen), and read only while not granted, as the real store is.
 */
private class FakeAccess(
    var status: LocalNetworkPermission,
    vararg answers: LocalNetworkPermission,
    var denied: Boolean = false,
) : LocalNetworkAccess {
    private val answers = answers.toMutableList()
    var requests = 0
    var settingsOpened = 0

    override fun status() = status

    override fun deniedBefore() = denied && (status == LocalNetworkPermission.CanAsk || status == LocalNetworkPermission.Blocked)

    override suspend fun request(): LocalNetworkPermission {
        requests++
        status = answers.removeAt(0)
        denied = status != LocalNetworkPermission.Granted
        return status
    }

    override fun openSettings() {
        settingsOpened++
    }
}

class HomeLocalNetworkViewModelTest {
    // Unconfined, outside runBlocking: the fake's answers arrive before allow() returns.
    private fun viewModel(servers: ServerRepository, access: LocalNetworkAccess) =
        HomeLocalNetworkViewModel(servers, access, CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun internetOnlyServersAreNeverAsked() {
        for (status in LocalNetworkPermission.entries) {
            val access = FakeAccess(status)
            val viewModel = viewModel(Servers(server("a", AddressScope.Internet), server("b", AddressScope.Internet)), access)
            assertEquals(HomeLocalNetworkState.Hidden, viewModel.state.value, "$status")
            viewModel.refresh()
            assertEquals(HomeLocalNetworkState.Hidden, viewModel.state.value, "$status")
            assertEquals(0, access.requests)
        }
    }

    @Test
    fun withoutServersNothingIsAsked() {
        val viewModel = viewModel(Servers(), FakeAccess(LocalNetworkPermission.CanAsk))
        assertEquals(HomeLocalNetworkState.Hidden, viewModel.state.value)
    }

    @Test
    fun nothingToAskStaysHiddenWithALocalServer() {
        for (status in listOf(LocalNetworkPermission.NotNeeded, LocalNetworkPermission.Granted)) {
            val viewModel = viewModel(Servers(server("a", AddressScope.Local)), FakeAccess(status))
            assertEquals(HomeLocalNetworkState.Hidden, viewModel.state.value, "$status")
        }
    }

    @Test
    fun aLocalAddressExplainsFirstAndAsksOnlyOnContinue() {
        // One local address among internet ones is enough.
        val access = FakeAccess(LocalNetworkPermission.CanAsk, LocalNetworkPermission.Granted)
        val viewModel = viewModel(Servers(server("a", AddressScope.Internet, AddressScope.Local)), access)
        assertEquals(HomeLocalNetworkState.Ask(), viewModel.state.value)
        assertEquals(0, access.requests)
        viewModel.allow()
        assertEquals(1, access.requests)
        assertEquals(HomeLocalNetworkState.Hidden, viewModel.state.value)
    }

    @Test
    fun aDenialIsShownAndAskedAgainOnlyOnRequest() {
        val access = FakeAccess(LocalNetworkPermission.CanAsk, LocalNetworkPermission.CanAsk, LocalNetworkPermission.Blocked)
        val viewModel = viewModel(Servers(server("a", AddressScope.Local)), access)
        viewModel.allow()
        assertEquals(HomeLocalNetworkState.Ask(denied = true), viewModel.state.value)
        // Still denied after coming back to the page, without a new dialog.
        viewModel.refresh()
        assertEquals(HomeLocalNetworkState.Ask(denied = true), viewModel.state.value)
        assertEquals(1, access.requests)
        viewModel.allow()
        assertEquals(HomeLocalNetworkState.Blocked, viewModel.state.value)
        assertEquals(2, access.requests)
    }

    @Test
    fun blockedOffersTheSettingsAndNoticesAGrantThere() {
        val access = FakeAccess(LocalNetworkPermission.Blocked)
        val viewModel = viewModel(Servers(server("a", AddressScope.Local)), access)
        assertEquals(HomeLocalNetworkState.Blocked, viewModel.state.value)
        viewModel.openSettings()
        assertEquals(1, access.settingsOpened)
        access.status = LocalNetworkPermission.Granted
        viewModel.refresh()
        assertEquals(HomeLocalNetworkState.Hidden, viewModel.state.value)
    }

    @Test
    fun aDenialOnAnotherScreenIsShownAtStartWithoutAsking() {
        // "Don't allow" on Add server, then Home: the denied note with "Ask again", and no dialog until it is pressed.
        val access = FakeAccess(LocalNetworkPermission.CanAsk, LocalNetworkPermission.Granted, denied = true)
        val viewModel = viewModel(Servers(server("a", AddressScope.Local)), access)
        assertEquals(HomeLocalNetworkState.Ask(denied = true), viewModel.state.value)
        viewModel.refresh()
        assertEquals(0, access.requests)
        viewModel.allow()
        assertEquals(1, access.requests)
        assertEquals(HomeLocalNetworkState.Hidden, viewModel.state.value)
    }

    @Test
    fun aRequestNoScreenCouldShowIsNotADenial() {
        // No activity attached: request() returns the unchanged state and records nothing, so the note stays the plain one.
        val access = object : LocalNetworkAccess {
            var requests = 0
            override fun status() = LocalNetworkPermission.CanAsk
            override fun deniedBefore() = false
            override suspend fun request(): LocalNetworkPermission {
                requests++
                return LocalNetworkPermission.CanAsk
            }
            override fun openSettings() = Unit
        }
        val viewModel = viewModel(Servers(server("a", AddressScope.Local)), access)
        viewModel.allow()
        assertEquals(1, access.requests)
        assertEquals(HomeLocalNetworkState.Ask(), viewModel.state.value)
    }

    @Test
    fun resumingWhileTheDialogIsUpKeepsAsking() {
        // The system dialog pauses and resumes the activity mid-request.
        val gate = CompletableDeferred<LocalNetworkPermission>()
        val access = object : LocalNetworkAccess {
            var requests = 0
            override fun status() = LocalNetworkPermission.CanAsk
            override fun deniedBefore() = false
            override suspend fun request(): LocalNetworkPermission {
                requests++
                return gate.await()
            }
            override fun openSettings() = Unit
        }
        val viewModel = viewModel(Servers(server("a", AddressScope.Local)), access)
        viewModel.allow()
        assertEquals(HomeLocalNetworkState.Asking, viewModel.state.value)
        viewModel.refresh()
        viewModel.allow()
        assertEquals(HomeLocalNetworkState.Asking, viewModel.state.value)
        gate.complete(LocalNetworkPermission.Granted)
        assertEquals(HomeLocalNetworkState.Hidden, viewModel.state.value)
        assertEquals(1, access.requests)
    }

    @Test
    fun aGrantClearsTheDenial() {
        val access = FakeAccess(LocalNetworkPermission.CanAsk, LocalNetworkPermission.CanAsk)
        val viewModel = viewModel(Servers(server("a", AddressScope.Local)), access)
        viewModel.allow()
        access.status = LocalNetworkPermission.Granted
        access.denied = false
        viewModel.refresh()
        // Android may reset its grants; the next ask starts with the plain explanation.
        access.status = LocalNetworkPermission.CanAsk
        viewModel.refresh()
        assertEquals(HomeLocalNetworkState.Ask(), viewModel.state.value)
    }

    @Test
    fun savedServersChangeWhetherToAsk() {
        val servers = Servers(server("a", AddressScope.Internet))
        val viewModel = viewModel(servers, FakeAccess(LocalNetworkPermission.CanAsk))
        assertEquals(HomeLocalNetworkState.Hidden, viewModel.state.value)
        servers.save(server("b", AddressScope.Local))
        assertEquals(HomeLocalNetworkState.Ask(), viewModel.state.value)
        servers.remove(ServerRef(ServiceType.jellyfin, "b"))
        assertEquals(HomeLocalNetworkState.Hidden, viewModel.state.value)
    }
}
