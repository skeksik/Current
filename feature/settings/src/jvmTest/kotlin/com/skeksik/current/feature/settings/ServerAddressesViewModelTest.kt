package com.skeksik.current.feature.settings

import com.skeksik.current.core.model.AddressScope
import com.skeksik.current.core.model.SavedServer
import com.skeksik.current.core.model.ServerAddress
import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServerStatus
import com.skeksik.current.core.model.ServiceType
import com.skeksik.current.core.network.MutableCapabilitiesState
import com.skeksik.current.core.network.ServerAddressSource
import com.skeksik.current.core.network.ServerAddressSources
import com.skeksik.current.core.network.ServerRepository
import com.skeksik.current.core.network.ServerUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
        override val checking = MutableStateFlow(false)
        var refreshes = 0

        override fun reportUnreachable(address: ServerUrl) = Unit

        override fun refresh() {
            refreshes++
        }
    }

    private val servers = FakeServers(listOf(home))
    private val source = FakeSource()
    private val capabilities = MutableCapabilitiesState()
    private fun TestScope.viewModel() = ServerAddressesViewModel(ref, servers, ServerAddressSources { source }, capabilities, backgroundScope)

    /** The state once every change so far has reached it. */
    private fun TestScope.shown(viewModel: ServerAddressesViewModel): ServerAddressesUiState {
        runCurrent()
        return viewModel.state.value
    }

    private fun ServerAddressesUiState.urls() = addresses.map { it.url }

    @Test
    fun theAddressesAreListedInPriorityOrderWithTheOneInUse() = runTest {
        val viewModel = viewModel()
        assertEquals(listOf(ip.url, lan.url, internet.url), viewModel.state.value.urls())
        assertEquals("Home", viewModel.state.value.name)
        source.current.value = ServerUrl.parse(internet.url)
        val shown = shown(viewModel)
        assertEquals(listOf(false, false, true), shown.addresses.map { it.inUse })
        assertEquals(listOf(AddressScope.Local, AddressScope.Local, AddressScope.Internet), shown.addresses.map { it.scope })
    }

    @Test
    fun plainHttpOnTheInternetIsFlagged() = runTest {
        servers.save(home.copy(addresses = listOf(ip, plainInternet)))
        val rows = viewModel().state.value.addresses
        assertEquals(listOf(false, true), rows.map { it.plainHttpOnInternet })
    }

    @Test
    fun movingChangesThePriorityOrder() = runTest {
        val viewModel = viewModel()
        viewModel.moveUp(internet.url)
        assertEquals(listOf(ip, internet, lan), servers.server(ref)!!.addresses)
        viewModel.moveDown(ip.url)
        assertEquals(listOf(internet, ip, lan), servers.server(ref)!!.addresses)
        viewModel.move(2, 0)
        assertEquals(listOf(lan, internet, ip), servers.server(ref)!!.addresses)
        assertEquals(listOf(lan.url, internet.url, ip.url), shown(viewModel).urls())
    }

    @Test
    fun movingPastEitherEndChangesNothing() = runTest {
        val viewModel = viewModel()
        viewModel.moveUp(ip.url)
        viewModel.moveDown(internet.url)
        viewModel.move(0, 3)
        viewModel.moveUp("https://unknown.example.com")
        assertEquals(home, servers.server(ref))
    }

    @Test
    fun removingKeepsAtLeastOneAddress() = runTest {
        val viewModel = viewModel()
        viewModel.remove(lan.url)
        viewModel.remove(internet.url)
        assertEquals(listOf(ip), servers.server(ref)!!.addresses)
        val last = shown(viewModel).addresses.single()
        assertFalse(last.canRemove)
        viewModel.remove(ip.url)
        assertEquals(listOf(ip), servers.server(ref)!!.addresses)
    }

    @Test
    fun everyAddressCanBeRemovedWhileThereAreSeveral() = runTest {
        assertTrue(viewModel().state.value.addresses.all { it.canRemove })
    }

    @Test
    fun aRemovedServerIsNoLongerShown() = runTest {
        val viewModel = viewModel()
        servers.remove(ref)
        assertFalse(shown(viewModel).saved)
        viewModel.remove(ip.url)
        assertTrue(servers.servers.value.isEmpty())
    }

    @Test
    fun reachabilityIsUnknownUntilChecked() = runTest {
        assertNull(viewModel().state.value.reachable)
    }

    @Test
    fun reachableFollowsThisServersStatusOnly() = runTest {
        // Another saved server that cannot be reached says nothing about this one.
        capabilities.put(ServerStatus(ServerRef(ServiceType.jellyfin, "b"), reachable = false))
        capabilities.put(ServerStatus(ref, reachable = true))
        val viewModel = viewModel()
        assertEquals(true, viewModel.state.value.reachable)
        capabilities.update(ref) { it.copy(reachable = false) }
        assertEquals(false, shown(viewModel).reachable)
        capabilities.update(ref) { it.copy(reachable = true) }
        source.current.value = ServerUrl.parse(lan.url)
        val shown = shown(viewModel)
        assertEquals(true, shown.reachable)
        assertEquals(listOf(false, true, false), shown.addresses.map { it.inUse })
    }

    @Test
    fun theStatusIsCheckingUntilTheServerHasBeenChecked() = runTest {
        capabilities.put(ServerStatus(ref))
        val viewModel = viewModel()
        source.checking.value = true
        assertEquals(AddressesStatus.Checking, shown(viewModel).status)
        assertNull(shown(viewModel).inUse)
        source.current.value = ServerUrl.parse(internet.url)
        capabilities.update(ref) { it.copy(reachable = true) }
        source.checking.value = false
        val shown = shown(viewModel)
        assertEquals(AddressesStatus.Reachable, shown.status)
        assertEquals(InUseAddress(internet.url, AddressScope.Internet), shown.inUse)
        assertFalse(shown.checking)
    }

    @Test
    fun aCheckWithAnAddressInUseKeepsTheStatusShown() = runTest {
        capabilities.put(ServerStatus(ref, reachable = true))
        source.current.value = ServerUrl.parse(ip.url)
        val viewModel = viewModel()
        source.checking.value = true
        val shown = shown(viewModel)
        assertEquals(AddressesStatus.Reachable, shown.status, "a periodic check does not make the status flicker")
        assertTrue(shown.checking)
        assertEquals(InUseAddress(ip.url, AddressScope.Local), shown.inUse)
    }

    @Test
    fun theServerIsOfflineWhenNoAddressAnswers() = runTest {
        capabilities.put(ServerStatus(ref, reachable = false))
        val viewModel = viewModel()
        assertEquals(AddressesStatus.Offline, shown(viewModel).status)
        source.checking.value = true
        assertEquals(AddressesStatus.Checking, shown(viewModel).status, "a check with no address in use may find one")
    }

    @Test
    fun refreshAsksTheSourceUnlessACheckRuns() = runTest {
        val viewModel = viewModel()
        runCurrent()
        viewModel.refresh()
        assertEquals(1, source.refreshes)
        source.checking.value = true
        runCurrent()
        viewModel.refresh()
        assertEquals(1, source.refreshes)
        servers.remove(ref)
        source.checking.value = false
        runCurrent()
        viewModel.refresh()
        assertEquals(1, source.refreshes, "a removed server is not checked")
    }
}
