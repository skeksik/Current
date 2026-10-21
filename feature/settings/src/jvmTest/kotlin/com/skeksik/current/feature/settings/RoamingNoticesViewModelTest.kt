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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class RoamingNoticesViewModelTest {
    private val home = ServerRef(ServiceType.jellyfin, "a")
    private val office = ServerRef(ServiceType.jellyfin, "b")
    private val local = ServerAddress("http://192.168.1.10:8096", AddressScope.Local)
    private val internet = ServerAddress("https://media.example.com", AddressScope.Internet)
    private val homeServer = SavedServer(home, "0123456789abcdef0123456789abcdef", "Living room", listOf(local, internet))
    private val officeServer = SavedServer(office, "fedcba9876543210fedcba9876543210", "Office", listOf(internet))

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

    /** One address source per server, whose switches the test sends. */
    private class FakeSource : ServerAddressSource {
        override val current = MutableStateFlow<ServerUrl?>(null)
        override val switches = MutableSharedFlow<ServerUrl>(extraBufferCapacity = 8)

        override fun reportUnreachable(address: ServerUrl) = Unit
    }

    private val servers = FakeServers(listOf(homeServer, officeServer))
    private val sources = mapOf(home to FakeSource(), office to FakeSource())

    private fun TestScope.viewModel(): RoamingNoticesViewModel {
        val viewModel = RoamingNoticesViewModel(servers, ServerAddressSources { sources.getValue(it.ref) }, backgroundScope)
        runCurrent()
        return viewModel
    }

    private fun TestScope.switch(ref: ServerRef, address: ServerAddress) {
        sources.getValue(ref).switches.tryEmit(ServerUrl.parse(address.url)!!)
        runCurrent()
    }

    @Test
    fun aSwitchShowsTheServerAndTheNetworkKind() = runTest {
        val viewModel = viewModel()
        assertNull(viewModel.state.value)
        switch(home, local)
        assertEquals(RoamingNotice(home, "Living room", AddressScope.Local), viewModel.state.value)
    }

    @Test
    fun theNoticeClearsItselfAfterAFewSeconds() = runTest {
        val viewModel = viewModel()
        switch(home, internet)
        advanceTimeBy(3.9.seconds)
        runCurrent()
        assertEquals(AddressScope.Internet, viewModel.state.value?.scope)
        advanceTimeBy(0.2.seconds)
        runCurrent()
        assertNull(viewModel.state.value)
    }

    @Test
    fun aNewerSwitchReplacesTheNoticeAndStartsItsTimeOver() = runTest {
        val viewModel = viewModel()
        switch(home, internet)
        advanceTimeBy(3.seconds)
        switch(office, internet)
        assertEquals(RoamingNotice(office, "Office", AddressScope.Internet), viewModel.state.value)
        advanceTimeBy(3.seconds)
        runCurrent()
        assertEquals(office, viewModel.state.value?.server, "the first notice's time does not clear the second")
        advanceTimeBy(1.1.seconds)
        runCurrent()
        assertNull(viewModel.state.value)
    }

    @Test
    fun dismissClearsTheNotice() = runTest {
        val viewModel = viewModel()
        switch(home, local)
        viewModel.dismiss()
        assertNull(viewModel.state.value)
    }

    @Test
    fun anEditOfTheAddressesShowsNothing() = runTest {
        // The source reports no switch for an address change an edit caused (RoamingServerAddressTest); the notices follow the edit.
        val viewModel = viewModel()
        servers.save(homeServer.copy(addresses = listOf(internet, local)))
        runCurrent()
        assertNull(viewModel.state.value)
        switch(home, local)
        assertEquals(AddressScope.Local, viewModel.state.value?.scope, "still listening after the edit")
    }

    @Test
    fun aRemovedServerShowsNothing() = runTest {
        val viewModel = viewModel()
        servers.remove(home)
        runCurrent()
        switch(home, local)
        assertNull(viewModel.state.value)
        servers.save(homeServer)
        runCurrent()
        switch(home, local)
        assertEquals(home, viewModel.state.value?.server, "listened to again once saved again")
    }
}
