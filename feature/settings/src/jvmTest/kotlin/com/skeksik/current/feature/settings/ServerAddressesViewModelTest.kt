package com.skeksik.current.feature.settings

import com.skeksik.current.core.model.AddressScope
import com.skeksik.current.core.model.SavedServer
import com.skeksik.current.core.model.ServerAddress
import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServiceType
import com.skeksik.current.core.network.ServerAddressSource
import com.skeksik.current.core.network.ServerAddressSources
import com.skeksik.current.core.network.ServerRepository
import com.skeksik.current.core.network.ServerUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerAddressesViewModelTest {
    private val ref = ServerRef(ServiceType.jellyfin, "a")
    private val ip = ServerAddress("http://192.168.1.10:8096", AddressScope.Local)
    private val lan = ServerAddress("http://media.lan:8096", AddressScope.Local)
    private val internet = ServerAddress("https://media.example.com", AddressScope.Internet)
    private val plainInternet = ServerAddress("http://media.example.com", AddressScope.Internet)
    private val home = SavedServer(ref, "0123456789abcdef0123456789abcdef", "Home", listOf(ip, lan, internet))

    /** Keeps the order servers were added in, as the real repository does. */
    private class FakeServers(initial: List<SavedServer>) : ServerRepository {
        private val state = MutableStateFlow(initial)
        override val servers: StateFlow<List<SavedServer>> = state

        override fun save(server: SavedServer) {
            state.value = if (state.value.any { it.ref == server.ref }) state.value.map { if (it.ref == server.ref) server else it }
            else state.value + server
        }

        override fun remove(ref: ServerRef) {
            state.value = state.value.filterNot { it.ref == ref }
        }
    }

    /** One address source whose address in use the test sets. */
    private class FakeSource : ServerAddressSource {
        override val current = MutableStateFlow<ServerUrl?>(null)

        override fun reportUnreachable(address: ServerUrl) = Unit
    }

    private val servers = FakeServers(listOf(home))
    private val source = FakeSource()
    private val scope = CoroutineScope(Job())

    @AfterTest
    fun stop() = scope.cancel()

    private fun viewModel() = ServerAddressesViewModel(ref, servers, ServerAddressSources { source }, scope)

    private suspend fun ServerAddressesViewModel.awaitState(predicate: (ServerAddressesUiState) -> Boolean) =
        withTimeout(5_000) { state.first(predicate) }

    private fun ServerAddressesUiState.urls() = addresses.map { it.url }

    @Test
    fun theAddressesAreListedInPriorityOrderWithTheOneInUse() = runBlocking {
        val viewModel = viewModel()
        assertEquals(listOf(ip.url, lan.url, internet.url), viewModel.state.value.urls())
        assertEquals("Home", viewModel.state.value.name)
        source.current.value = ServerUrl.parse(internet.url)
        val shown = viewModel.awaitState { state -> state.addresses.any { it.inUse } }
        assertEquals(listOf(false, false, true), shown.addresses.map { it.inUse })
        assertEquals(listOf(AddressScope.Local, AddressScope.Local, AddressScope.Internet), shown.addresses.map { it.scope })
    }

    @Test
    fun plainHttpOnTheInternetIsFlagged() = runBlocking {
        servers.save(home.copy(addresses = listOf(ip, plainInternet)))
        val rows = viewModel().state.value.addresses
        assertEquals(listOf(false, true), rows.map { it.plainHttpOnInternet })
    }

    @Test
    fun movingChangesThePriorityOrder() = runBlocking {
        val viewModel = viewModel()
        viewModel.moveUp(internet.url)
        assertEquals(listOf(ip, internet, lan), servers.server(ref)!!.addresses)
        viewModel.moveDown(ip.url)
        assertEquals(listOf(internet, ip, lan), servers.server(ref)!!.addresses)
        viewModel.move(2, 0)
        assertEquals(listOf(lan, internet, ip), servers.server(ref)!!.addresses)
        viewModel.awaitState { it.urls() == listOf(lan.url, internet.url, ip.url) }
        Unit
    }

    @Test
    fun movingPastEitherEndChangesNothing() = runBlocking {
        val viewModel = viewModel()
        viewModel.moveUp(ip.url)
        viewModel.moveDown(internet.url)
        viewModel.move(0, 3)
        viewModel.moveUp("https://unknown.example.com")
        assertEquals(home, servers.server(ref))
    }

    @Test
    fun removingKeepsAtLeastOneAddress() = runBlocking {
        val viewModel = viewModel()
        viewModel.remove(lan.url)
        viewModel.remove(internet.url)
        assertEquals(listOf(ip), servers.server(ref)!!.addresses)
        val last = viewModel.awaitState { it.addresses.size == 1 }.addresses.single()
        assertFalse(last.canRemove)
        viewModel.remove(ip.url)
        assertEquals(listOf(ip), servers.server(ref)!!.addresses)
    }

    @Test
    fun everyAddressCanBeRemovedWhileThereAreSeveral() = runBlocking {
        assertTrue(viewModel().state.value.addresses.all { it.canRemove })
    }

    @Test
    fun aRemovedServerIsNoLongerShown() = runBlocking {
        val viewModel = viewModel()
        servers.remove(ref)
        viewModel.awaitState { !it.saved }
        viewModel.remove(ip.url)
        assertTrue(servers.servers.value.isEmpty())
    }
}
