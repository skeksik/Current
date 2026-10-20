package com.skeksik.current.core.network

import com.skeksik.current.core.model.AddressScope
import com.skeksik.current.core.model.SavedServer
import com.skeksik.current.core.model.ServerAddress
import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServerStatus
import com.skeksik.current.core.model.ServiceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

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

    /** Answers per address: a server id, or "slow" (times out after 5 s, as Jellyfin's probe does); anything else is unreachable. */
    private class FakeProbe(val answers: MutableMap<String, String> = mutableMapOf()) : ServerProbe {
        override val service = ServiceType.jellyfin
        override val defaultPort = 8096
        val asked = mutableListOf<String>()

        override suspend fun identify(server: ServerRef, address: ServerUrl): ServerIdentity {
            asked += address.toString()
            return when (val answer = answers[address.toString()]) {
                null -> throw ServiceException(server, ServiceErrorKind.Unreachable)
                "slow" -> {
                    delay(5.seconds)
                    throw ServiceException(server, ServiceErrorKind.Timeout)
                }
                else -> ServerIdentity(answer, "Home", "12.2.0")
            }
        }
    }

    private val probe = FakeProbe()
    private val changes = MutableSharedFlow<Unit>()
    private val capabilities = MutableCapabilitiesState().apply { put(ServerStatus(ref)) }

    /** Runs the source in the background, so it stops with the test, and lets its first check finish. */
    private fun TestScope.roaming(server: SavedServer = saved): RoamingServerAddress {
        val source = RoamingServerAddress(server, probe, capabilities, RoamingTiming())
        backgroundScope.launch { source.run(changes) }
        runCurrent()
        return source
    }

    /** Moves virtual time on by [by] and runs what is due then. */
    private fun TestScope.advance(by: Duration) {
        advanceTimeBy(by)
        runCurrent()
    }

    private fun url(text: String) = ServerUrl.parse(text)!!

    private val reachable get() = capabilities.capabilities.value.status(ref)?.reachable

    @Test
    fun theFirstAddressThatAnswersInPriorityOrderIsUsed() = runTest {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val source = roaming()
        assertEquals(url(local), source.current.value)
        assertEquals(true, reachable)
    }

    @Test
    fun aLowerAddressIsUsedWhenTheFirstDoesNotAnswer() = runTest {
        probe.answers += mapOf(internet to homeId)
        val source = roaming()
        assertEquals(url(internet), source.current.value)
    }

    @Test
    fun anAddressAtWhichAnotherServerAnswersIsNeverUsed() = runTest {
        probe.answers += mapOf(local to otherId, internet to homeId)
        val source = roaming()
        assertEquals(url(internet), source.current.value)
    }

    @Test
    fun aHigherAddressThatHasNotAnsweredYetIsWaitedFor() = runTest {
        probe.answers += mapOf(local to "slow", internet to homeId)
        val source = roaming()
        // The probe's own timeout bounds the wait; until it fails, nothing is chosen over it.
        assertNull(source.current.value)
        advance(5.seconds)
        assertEquals(url(internet), source.current.value)
    }

    @Test
    fun noAddressWhenNothingAnswersAsTheSavedServer() = runTest {
        probe.answers += mapOf(local to otherId)
        val source = roaming()
        assertNull(source.current.value)
        assertEquals(false, reachable)
    }

    @Test
    fun whileNothingAnswersItAsksAgainWithGrowingWaits() = runTest {
        val source = roaming()
        assertEquals(2, probe.asked.size)
        advance(14.seconds)
        assertEquals(2, probe.asked.size)
        advance(1.seconds)
        assertEquals(4, probe.asked.size)
        advance(15.seconds)
        assertEquals(4, probe.asked.size, "the second wait is 30 s")
        probe.answers[internet] = homeId
        advance(15.seconds)
        assertEquals(url(internet), source.current.value)
        assertEquals(true, reachable)
    }

    @Test
    fun thePeriodicCheckMovesBackToAHigherAddressAndAsksOnlyThoseAbove() = runTest {
        probe.answers += mapOf(internet to homeId)
        val source = roaming()
        assertEquals(url(internet), source.current.value)
        probe.asked.clear()
        probe.answers[local] = homeId
        advance(4.minutes)
        assertEquals(url(internet), source.current.value)
        advance(1.minutes)
        assertEquals(listOf(local), probe.asked)
        assertEquals(url(local), source.current.value)
        probe.asked.clear()
        advance(1_000.minutes)
        assertTrue(probe.asked.isEmpty(), "on the first address there is nothing to move back to")
    }

    @Test
    fun aFailedPeriodicCheckKeepsTheAddressInUse() = runTest {
        probe.answers += mapOf(internet to homeId)
        val source = roaming()
        advance(5.minutes)
        assertEquals(url(internet), source.current.value)
        assertEquals(true, reachable)
    }

    @Test
    fun aNetworkChangeAsksEveryAddressAgain() = runTest {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val source = roaming()
        probe.answers.remove(local)
        probe.asked.clear()
        changes.emit(Unit)
        runCurrent()
        assertEquals(setOf(local, internet), probe.asked.toSet())
        assertEquals(url(internet), source.current.value)
    }

    @Test
    fun aFailedRequestToTheAddressInUseMovesOn() = runTest {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val source = roaming()
        probe.answers.remove(local)
        source.reportUnreachable(url(local))
        runCurrent()
        assertEquals(url(internet), source.current.value)
    }

    @Test
    fun aFailureReportedForAnAddressNoLongerInUseIsIgnored() = runTest {
        probe.answers += mapOf(internet to homeId)
        val source = roaming()
        probe.asked.clear()
        source.reportUnreachable(url(local))
        runCurrent()
        assertTrue(probe.asked.isEmpty())
    }

    @Test
    fun theAddressInUseStaysWhileACheckRuns() = runTest {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val source = roaming()
        probe.answers[local] = "slow"
        changes.emit(Unit)
        runCurrent()
        assertEquals(url(local), source.current.value)
    }

    @Test
    fun aRemovedAddressIsDroppedAtOnce() = runTest {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val source = roaming()
        probe.answers[internet] = "slow"
        source.update(saved.withoutAddress(local))
        assertNull(source.current.value)
    }

    @Test
    fun reorderingAsksAgainInTheNewOrder() = runTest {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val source = roaming()
        source.update(saved.withAddressMoved(1, 0))
        runCurrent()
        assertEquals(url(internet), source.current.value)
    }

    @Test
    fun anAddedAddressIsTriedInItsPlace() = runTest {
        probe.answers += mapOf(internet to homeId, lan to homeId)
        val source = roaming()
        assertEquals(url(internet), source.current.value)
        source.update(saved.withAddress(ServerAddress(lan, AddressScope.Local)).withAddressMoved(2, 0))
        runCurrent()
        assertEquals(url(lan), source.current.value)
    }

    @Test
    fun aRenameAsksNothing() = runTest {
        probe.answers += mapOf(local to homeId)
        val source = roaming()
        probe.asked.clear()
        source.update(saved.copy(name = "Renamed"))
        runCurrent()
        assertTrue(probe.asked.isEmpty())
        assertFalse(source.current.value == null)
    }
}
