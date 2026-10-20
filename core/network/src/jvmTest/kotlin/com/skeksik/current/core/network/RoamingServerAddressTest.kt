package com.skeksik.current.core.network

import com.skeksik.current.core.model.AddressScope
import com.skeksik.current.core.model.SavedServer
import com.skeksik.current.core.model.ServerAddress
import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServerStatus
import com.skeksik.current.core.model.ServiceType
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class RoamingServerAddressTest {
    private val ref = ServerRef(ServiceType.jellyfin, "a")
    private val homeId = "0123456789abcdef0123456789abcdef"
    private val otherId = "fedcba9876543210fedcba9876543210"
    private val local = "http://192.168.1.10:8096"
    private val lan = "http://media.lan:8096"
    private val internet = "https://media.example.com"

    private val saved = SavedServer(
        ref,
        homeId,
        "Home",
        listOf(ServerAddress(local, AddressScope.Local), ServerAddress(internet, AddressScope.Internet)),
    )

    /** Answers per address: a server id, or "hang"; anything else is unreachable. */
    private class FakeProbe(val answers: MutableMap<String, String> = mutableMapOf()) : ServerProbe {
        override val service = ServiceType.jellyfin
        override val defaultPort = 8096
        val asked = mutableListOf<String>()

        override suspend fun identify(server: ServerRef, address: ServerUrl): ServerIdentity {
            asked += address.toString()
            return when (val answer = answers[address.toString()]) {
                null -> throw ServiceException(server, ServiceErrorKind.Unreachable)
                "hang" -> awaitCancellation()
                else -> ServerIdentity(answer, "Home", "12.2.0")
            }
        }
    }

    private val probe = FakeProbe()
    private val clock = TestClock()
    private val changes = MutableSharedFlow<Unit>()
    private val capabilities = MutableCapabilitiesState().apply { put(ServerStatus(ref)) }
    private var job: Job? = null

    @AfterTest
    fun stop() {
        job?.cancel()
    }

    private suspend fun CoroutineScope.roaming(server: SavedServer = saved): RoamingServerAddress {
        val source = RoamingServerAddress(server, probe, capabilities, RoamingTiming(), clock::sleep)
        job = launch { source.run(changes) }
        settle()
        return source
    }

    private fun url(text: String) = ServerUrl.parse(text)!!

    private val reachable get() = capabilities.capabilities.value.status(ref)?.reachable

    @Test
    fun theFirstAddressThatAnswersInPriorityOrderIsUsed() = runBlocking {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val source = roaming()
        assertEquals(url(local), source.current.value)
        assertEquals(true, reachable)
        job?.cancel()
    }

    @Test
    fun aLowerAddressIsUsedWhenTheFirstDoesNotAnswer() = runBlocking {
        probe.answers += mapOf(internet to homeId)
        val source = roaming()
        assertEquals(url(internet), source.current.value)
        job?.cancel()
    }

    @Test
    fun anAddressAtWhichAnotherServerAnswersIsNeverUsed() = runBlocking {
        probe.answers += mapOf(local to otherId, internet to homeId)
        val source = roaming()
        assertEquals(url(internet), source.current.value)
        job?.cancel()
    }

    @Test
    fun aHigherAddressThatHasNotAnsweredYetIsWaitedFor() = runBlocking {
        probe.answers += mapOf(local to "hang", internet to homeId)
        val source = roaming()
        // The probe's own timeout bounds the wait; until it fails, nothing is chosen over it.
        assertNull(source.current.value)
        probe.answers.remove(local)
        changes.emit(Unit)
        settle()
        job?.cancel()
    }

    @Test
    fun noAddressWhenNothingAnswersAsTheSavedServer() = runBlocking {
        probe.answers += mapOf(local to otherId)
        val source = roaming()
        assertNull(source.current.value)
        assertEquals(false, reachable)
        job?.cancel()
    }

    @Test
    fun whileNothingAnswersItAsksAgainWithGrowingWaits() = runBlocking {
        val source = roaming()
        assertEquals(2, probe.asked.size)
        clock.advance(14.seconds)
        assertEquals(2, probe.asked.size)
        clock.advance(1.seconds)
        assertEquals(4, probe.asked.size)
        clock.advance(15.seconds)
        assertEquals(4, probe.asked.size, "the second wait is 30 s")
        probe.answers[internet] = homeId
        clock.advance(15.seconds)
        assertEquals(url(internet), source.current.value)
        assertEquals(true, reachable)
        job?.cancel()
    }

    @Test
    fun thePeriodicCheckMovesBackToAHigherAddressAndAsksOnlyThoseAbove() = runBlocking {
        probe.answers += mapOf(internet to homeId)
        val source = roaming()
        assertEquals(url(internet), source.current.value)
        probe.asked.clear()
        probe.answers[local] = homeId
        clock.advance(4.minutes)
        assertEquals(url(internet), source.current.value)
        clock.advance(1.minutes)
        assertEquals(listOf(local), probe.asked)
        assertEquals(url(local), source.current.value)
        assertEquals(0, clock.sleeping, "on the first address there is nothing to move back to")
        job?.cancel()
    }

    @Test
    fun aFailedPeriodicCheckKeepsTheAddressInUse() = runBlocking {
        probe.answers += mapOf(internet to homeId)
        val source = roaming()
        clock.advance(5.minutes)
        assertEquals(url(internet), source.current.value)
        assertEquals(true, reachable)
        job?.cancel()
    }

    @Test
    fun aNetworkChangeAsksEveryAddressAgain() = runBlocking {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val source = roaming()
        probe.answers.remove(local)
        probe.asked.clear()
        changes.emit(Unit)
        settle()
        assertEquals(setOf(local, internet), probe.asked.toSet())
        assertEquals(url(internet), source.current.value)
        job?.cancel()
    }

    @Test
    fun aFailedRequestToTheAddressInUseMovesOn() = runBlocking {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val source = roaming()
        probe.answers.remove(local)
        source.reportUnreachable(url(local))
        settle()
        assertEquals(url(internet), source.current.value)
        job?.cancel()
    }

    @Test
    fun aFailureReportedForAnAddressNoLongerInUseIsIgnored() = runBlocking {
        probe.answers += mapOf(internet to homeId)
        val source = roaming()
        probe.asked.clear()
        source.reportUnreachable(url(local))
        settle()
        assertTrue(probe.asked.isEmpty())
        job?.cancel()
    }

    @Test
    fun theAddressInUseStaysWhileACheckRuns() = runBlocking {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val source = roaming()
        probe.answers[local] = "hang"
        changes.emit(Unit)
        settle()
        assertEquals(url(local), source.current.value)
        job?.cancel()
    }

    @Test
    fun aRemovedAddressIsDroppedAtOnce() = runBlocking {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val source = roaming()
        probe.answers[internet] = "hang"
        source.update(saved.withoutAddress(local))
        assertNull(source.current.value)
        job?.cancel()
    }

    @Test
    fun reorderingAsksAgainInTheNewOrder() = runBlocking {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val source = roaming()
        source.update(saved.withAddressMoved(1, 0))
        settle()
        assertEquals(url(internet), source.current.value)
        job?.cancel()
    }

    @Test
    fun anAddedAddressIsTriedInItsPlace() = runBlocking {
        probe.answers += mapOf(internet to homeId, lan to homeId)
        val source = roaming()
        assertEquals(url(internet), source.current.value)
        source.update(saved.withAddress(ServerAddress(lan, AddressScope.Local)).withAddressMoved(2, 0))
        settle()
        assertEquals(url(lan), source.current.value)
        job?.cancel()
    }

    @Test
    fun aRenameAsksNothing() = runBlocking {
        probe.answers += mapOf(local to homeId)
        val source = roaming()
        probe.asked.clear()
        source.update(saved.copy(name = "Renamed"))
        settle()
        assertTrue(probe.asked.isEmpty())
        assertFalse(source.current.value == null)
        job?.cancel()
    }
}
