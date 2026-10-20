package com.skeksik.current.core.network

import com.skeksik.current.core.database.InMemoryKeyValueStore
import com.skeksik.current.core.model.AddressScope
import com.skeksik.current.core.model.SavedServer
import com.skeksik.current.core.model.ServerAddress
import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServiceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class RoamingAddressSourcesTest {
    private val homeId = "0123456789abcdef0123456789abcdef"
    private val local = ServerAddress("http://192.168.1.10:8096", AddressScope.Local)
    private val internet = ServerAddress("https://media.example.com", AddressScope.Internet)
    private val capabilities = MutableCapabilitiesState()
    private val repository = StoredServerRepository(InMemoryKeyValueStore(), capabilities)
    private val changes = MutableSharedFlow<Unit>()

    /** The saved server answers everywhere. */
    private val probe = object : ServerProbe {
        override val service = ServiceType.jellyfin
        override val defaultPort = 8096

        override suspend fun identify(server: ServerRef, address: ServerUrl) = ServerIdentity(homeId, "Home", null)
    }

    private fun server(service: ServiceType = ServiceType.jellyfin) =
        SavedServer(newServerRef(service), homeId, "Home", listOf(local, internet))

    /** Runs in the background, so every source stops with the test. */
    private fun TestScope.sources() = RoamingAddressSources(repository, listOf(probe), capabilities, { changes }, backgroundScope)

    private fun url(address: ServerAddress) = ServerUrl.parse(address.url)!!

    @Test
    fun everyClientOfAServerSharesOneSource() = runTest {
        val home = server().also(repository::save)
        val sources = sources()
        assertSame(sources.forServer(home), sources.forServer(home))
        runCurrent()
        assertEquals(url(local), sources.forServer(home).current.value)
    }

    @Test
    fun aServerThatIsNotSavedGetsNoAddress() = runTest {
        val sources = sources()
        assertNull(sources.forServer(server()).current.value)
    }

    @Test
    fun editsToTheSavedServerReachItsSource() = runTest {
        val home = server().also(repository::save)
        val sources = sources()
        val source = sources.forServer(home)
        runCurrent()
        repository.save(home.withAddressMoved(1, 0))
        runCurrent()
        assertEquals(url(internet), source.current.value)
    }

    @Test
    fun aRemovedServerStopsAndANewSourceIsNotMadeForIt() = runTest {
        val home = server().also(repository::save)
        val sources = sources()
        val source = sources.forServer(home)
        runCurrent()
        repository.remove(home.ref)
        runCurrent()
        assertNull(sources.forServer(home).current.value)
        assertEquals(url(local), source.current.value, "a stopped source is no longer checked")
    }

    @Test
    fun aServiceWithoutAProbeUsesItsAddressesInOrder() = runTest {
        val stash = server(ServiceType("stash")).also(repository::save)
        val source = sources().forServer(stash)
        assertEquals(url(local), source.current.value)
        source.reportUnreachable(url(local))
        assertEquals(url(internet), source.current.value)
        source.reportUnreachable(url(local))
        assertEquals(url(internet), source.current.value, "a stale report is ignored")
        runCurrent()
        changes.emit(Unit)
        runCurrent()
        assertEquals(url(local), source.current.value)
    }
}
