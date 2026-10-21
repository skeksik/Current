package com.skeksik.current.core.network

import com.skeksik.current.core.database.InMemoryKeyValueStore
import com.skeksik.current.core.model.AddressScope
import com.skeksik.current.core.model.SavedServer
import com.skeksik.current.core.model.ServerAddress
import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServiceType
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.request.get
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource

class RoamingAddressSourcesTest {
    private val homeId = "0123456789abcdef0123456789abcdef"
    private val otherId = "fedcba9876543210fedcba9876543210"
    private val local = ServerAddress("http://192.168.1.10:8096", AddressScope.Local)
    private val internet = ServerAddress("https://media.example.com", AddressScope.Internet)
    private val capabilities = MutableCapabilitiesState()
    private val repository = StoredServerRepository(InMemoryKeyValueStore(), capabilities)
    private val changes = MutableSharedFlow<NetworkChange>()

    /** The saved server answers everywhere, unless [answers] says otherwise: another id, "late" (answers after 3 s) or "slow" (fails after 5 s). */
    private val probe = object : ServerProbe {
        override val service = ServiceType.jellyfin
        override val defaultPort = 8096
        val answers = mutableMapOf<String, String>()
        val asked = mutableListOf<String>()

        override suspend fun identify(server: ServerRef, address: ServerUrl): ServerIdentity {
            asked += address.toString()
            return when (val answer = answers[address.toString()]) {
                "late" -> {
                    delay(3.seconds)
                    ServerIdentity(homeId, "Home", null)
                }
                "slow" -> {
                    delay(5.seconds)
                    throw ServiceException(server, ServiceErrorKind.Timeout)
                }
                null -> ServerIdentity(homeId, "Home", null)
                else -> ServerIdentity(answer, "Home", null)
            }
        }
    }

    private fun server(service: ServiceType = ServiceType.jellyfin) =
        SavedServer(newServerRef(service), homeId, "Home", listOf(local, internet))

    /** Runs in the background, so every source stops with the test. */
    private fun TestScope.sources() = RoamingAddressSources(repository, listOf(probe), capabilities, { changes }, backgroundScope, time = testTimeSource)

    private fun url(address: ServerAddress) = ServerUrl.parse(address.url)!!

    @Test
    fun everySavedServerIsCheckedFromTheStart() = runTest {
        server().also(repository::save)
        sources()
        runCurrent()
        assertEquals(listOf(local.url, internet.url), probe.asked)
    }

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
        assertNull(sources.forServer(server()).awaitCurrent())
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
        assertNull(source.current.value)
        assertNull(sources.forServer(home).awaitCurrent())
        probe.asked.clear()
        changes.emit(NetworkChange.Changed)
        runCurrent()
        assertTrue(probe.asked.isEmpty(), "the stopped source asks nothing")
    }

    @Test
    fun aServiceThatCannotIdentifyItselfUsesItsAddressesInOrder() = runTest {
        val stash = server(ServiceType.stash).also(repository::save)
        val source = sources().forServer(stash)
        runCurrent()
        assertEquals(url(local), source.current.value)
        source.reportUnreachable(url(local))
        assertEquals(url(internet), source.awaitCurrent())
        source.reportUnreachable(url(local))
        runCurrent()
        assertEquals(url(internet), source.current.value, "a stale report is ignored")
        changes.emit(NetworkChange.Changed)
        runCurrent()
        assertEquals(url(local), source.current.value)
    }

    @Test
    fun anyOtherServiceWithoutAProbeGetsNoAddress() = runTest {
        val seerr = server(ServiceType.seerr).also(repository::save)
        assertNull(sources().forServer(seerr).awaitCurrent())
    }

    @Test
    fun aSourceWhoseLoopABugEndedStartsAgainAndTheBugSurfaces() = runTest {
        val bugs = mutableListOf<Throwable>()
        val appScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob() + CoroutineExceptionHandler { _, e -> bugs += e })
        var buggy = true
        val probe = object : ServerProbe {
            override val service = ServiceType.jellyfin
            override val defaultPort = 8096

            override suspend fun identify(server: ServerRef, address: ServerUrl): ServerIdentity {
                if (buggy) {
                    buggy = false
                    throw IllegalStateException("a bug in a probe")
                }
                return ServerIdentity(homeId, "Home", null)
            }
        }
        val home = server().also(repository::save)
        val sources = RoamingAddressSources(repository, listOf(probe), capabilities, { changes }, appScope, time = testTimeSource)
        val source = sources.forServer(home)
        runCurrent()
        assertEquals(listOf("a bug in a probe"), bugs.map { it.message }, "the bug is not swallowed")
        assertNull(source.current.value)
        advanceTimeBy(15.seconds)
        runCurrent()
        assertEquals(url(local), source.current.value, "the same source runs again")
        assertSame(source, sources.forServer(home))
        appScope.cancel()
    }

    /** A real client on a mock engine that runs on the test's clock, recording where each request went. */
    private fun TestScope.client(source: ServerAddressSource, sent: MutableList<String>): ServerClient {
        val engine = MockEngine(
            MockEngineConfig().apply {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    sent += request.url.host
                    respondOk()
                }
            },
        )
        return ServerClients(createHttpClient(engine)).create(ServerRef(ServiceType.jellyfin, "a"), source)
    }

    @Test
    fun aClientsFirstRequestWaitsForTheFirstProvedAddress() = runTest {
        probe.answers += mapOf(local.url to "slow", internet.url to "late")
        val home = server().also(repository::save)
        val sent = mutableListOf<String>()
        val client = client(sources().forServer(home), sent)
        val request = launch { client.request { get("Users/Me") } }
        runCurrent()
        assertTrue(sent.isEmpty())
        advanceTimeBy(3.seconds)
        runCurrent()
        request.join()
        assertEquals(listOf("media.example.com"), sent)
    }

    @Test
    fun afterANetworkChangeAClientSendsNothingUntilAnAddressIsProved() = runTest {
        val home = server().also(repository::save)
        val sent = mutableListOf<String>()
        val source = sources().forServer(home)
        runCurrent()
        assertEquals(url(local), source.current.value)
        // On the new network something slow answers at the old local address.
        probe.answers[local.url] = "slow"
        changes.emit(NetworkChange.Changed)
        runCurrent()
        val client = client(source, sent)
        val request = launch { client.request { get("Users/Me") } }
        runCurrent()
        assertTrue(sent.isEmpty(), "nothing is sent while the check runs")
        advanceTimeBy(1.seconds + 5.seconds)
        runCurrent()
        request.join()
        assertEquals(listOf("media.example.com"), sent)
    }
}
