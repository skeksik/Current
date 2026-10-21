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
import kotlinx.coroutines.job
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

    /**
     * An app scope on the test's clock that records the bugs reaching its handler. It is cancelled when the test ends, however it ends,
     * so a source it keeps restarting never outlives the test.
     */
    private fun TestScope.appScope(bugs: MutableList<Throwable> = mutableListOf()): CoroutineScope {
        val handler = CoroutineExceptionHandler { _, e -> bugs += e }
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob() + handler)
        backgroundScope.coroutineContext.job.invokeOnCompletion { scope.cancel() }
        return scope
    }

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
        val appScope = appScope(bugs)
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
    }

    @Test
    fun aSourceRestartedAfterABugNeverUsesAnAddressItHasNotProvedSince() = runTest {
        val bugs = mutableListOf<Throwable>()
        val appScope = appScope(bugs)
        val lan = ServerAddress("http://media.lan:8096", AddressScope.Local)
        // The first two addresses prove themselves, and their results are queued, when a bug in the third ends the run.
        val answers = mutableMapOf(lan.url to homeId, local.url to homeId, internet.url to "bug")
        val probe = object : ServerProbe {
            override val service = ServiceType.jellyfin
            override val defaultPort = 8096

            override suspend fun identify(server: ServerRef, address: ServerUrl): ServerIdentity {
                val answer = answers.getValue(address.toString())
                if (answer == "bug") throw IllegalStateException("a bug in a probe")
                return ServerIdentity(answer, "Home", null)
            }
        }
        val home = SavedServer(newServerRef(ServiceType.jellyfin), homeId, "Home", listOf(lan, local, internet)).also(repository::save)
        val sources = RoamingAddressSources(repository, listOf(probe), capabilities, { changes }, appScope, time = testTimeSource)
        val source = sources.forServer(home)
        runCurrent()
        assertEquals(1, bugs.size)
        val published = mutableListOf<ServerUrl?>()
        backgroundScope.launch { source.current.collect { published += it } }
        // In the gap, another server answers at both local addresses, and the user adds an address.
        answers += mapOf(lan.url to otherId, local.url to otherId, internet.url to homeId)
        val other = ServerAddress("https://other.example.com", AddressScope.Internet)
        answers[other.url] = otherId
        repository.save(home.withAddress(other))
        runCurrent()
        assertNull(source.awaitCurrent(), "requests in the gap wait and get nothing")
        advanceTimeBy(15.seconds)
        runCurrent()
        assertEquals(url(internet), source.current.value)
        assertTrue(published.none { it == url(lan) || it == url(local) }, "no unproved address was ever published: $published")
    }

    @Test
    fun aRecurringBugRestartsTheSourceLessAndLessOften() = runTest {
        val appScope = appScope()
        var runs = 0
        val probe = object : ServerProbe {
            override val service = ServiceType.jellyfin
            override val defaultPort = 8096

            override suspend fun identify(server: ServerRef, address: ServerUrl): ServerIdentity {
                runs++
                throw IllegalStateException("a bug in a probe")
            }
        }
        server().also(repository::save)
        RoamingAddressSources(repository, listOf(probe), capabilities, { changes }, appScope, time = testTimeSource)
        runCurrent()
        assertEquals(1, runs)
        advanceTimeBy(15.seconds)
        runCurrent()
        assertEquals(2, runs, "the first restart after 15 s")
        advanceTimeBy(29.seconds)
        runCurrent()
        assertEquals(2, runs)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(3, runs, "the next after 30 s")
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
