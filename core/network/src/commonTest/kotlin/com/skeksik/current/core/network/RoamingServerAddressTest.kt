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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource

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

    /**
     * Answers per address: a server id; "late" (the saved server, after 3 s); "slow" (times out after 5 s, as Jellyfin's probe does);
     * "hang" (never answers); anything else
     * is unreachable.
     */
    private inner class FakeProbe(val answers: MutableMap<String, String> = mutableMapOf()) : ServerProbe {
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
                "hang" -> awaitCancellation()
                "late" -> {
                    delay(3.seconds)
                    ServerIdentity(homeId, "Home", "12.2.0")
                }
                else -> ServerIdentity(answer, "Home", "12.2.0")
            }
        }
    }

    private val probe = FakeProbe()
    private val changes = MutableSharedFlow<NetworkChange>()
    private val capabilities = MutableCapabilitiesState().apply { put(ServerStatus(ref)) }

    /** Runs the source in the background, so it stops with the test, and lets its first check run as far as it can. */
    private fun TestScope.roaming(server: SavedServer = saved): RoamingServerAddress {
        val source = RoamingServerAddress(server, probe, capabilities, RoamingTiming(), testTimeSource)
        backgroundScope.launch { source.run(changes) }
        runCurrent()
        return source
    }

    /** Lets the rate limit on request-driven checks pass: 15 s since the last check ended. */
    private fun TestScope.waitOutTheRequestLimit() = advance(15.seconds)

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
    fun aLowerAddressIsUsedAtOnceWhileAHigherOneHasNotAnswered() = runTest {
        probe.answers += mapOf(local to "slow", internet to homeId)
        val source = roaming()
        assertEquals(url(internet), source.current.value, "not after the local probe's 5 s timeout")
        assertEquals(true, reachable)
        advance(5.seconds)
        assertEquals(url(internet), source.current.value)
    }

    @Test
    fun aHigherAddressThatAnswersLaterIsMovedTo() = runTest {
        probe.answers += mapOf(local to "late", internet to homeId)
        val source = roaming()
        assertEquals(url(internet), source.current.value)
        advance(3.seconds)
        assertEquals(url(local), source.current.value)
    }

    @Test
    fun noAddressWhenNothingAnswersAsTheSavedServer() = runTest {
        probe.answers += mapOf(local to otherId)
        val source = roaming()
        assertNull(source.current.value)
        assertNull(source.awaitCurrent())
        assertEquals(false, reachable)
    }

    @Test
    fun aRequestAtStartWaitsForTheFirstProvedAddress() = runTest {
        probe.answers += mapOf(local to "slow", internet to "late")
        val source = roaming()
        val request = async { source.awaitCurrent() }
        runCurrent()
        assertFalse(request.isCompleted)
        advance(3.seconds)
        assertEquals(url(internet), request.await())
    }

    @Test
    fun aRequestGivesUpWhenTheCheckTakesTooLong() = runTest {
        probe.answers += mapOf(local to "hang", internet to "hang")
        val source = roaming()
        val request = async { source.awaitCurrent() }
        advance(9.seconds)
        assertFalse(request.isCompleted)
        advance(1.seconds)
        assertNull(request.await())
    }

    @Test
    fun whileNothingAnswersItAsksAgainWithGrowingWaits() = runTest {
        roaming()
        assertEquals(2, probe.asked.size)
        advance(14.seconds)
        assertEquals(2, probe.asked.size)
        advance(1.seconds)
        assertEquals(4, probe.asked.size)
        advance(29.seconds)
        assertEquals(4, probe.asked.size, "the second wait is 30 s")
        advance(1.seconds)
        assertEquals(6, probe.asked.size)
    }

    @Test
    fun theWaitStopsGrowingAtFiveMinutesAndStartsOverAfterASuccess() = runTest {
        val source = roaming()
        // 15 s, 30 s, 1 min, 2 min, 4 min, then 5 min each.
        advance(15.seconds + 30.seconds + 1.minutes + 2.minutes + 4.minutes)
        assertEquals(12, probe.asked.size)
        advance(5.minutes - 1.seconds)
        assertEquals(12, probe.asked.size)
        advance(1.seconds)
        assertEquals(14, probe.asked.size)
        probe.answers[internet] = homeId
        advance(5.minutes)
        assertEquals(url(internet), source.current.value)
        probe.answers.clear()
        waitOutTheRequestLimit()
        source.reportUnreachable(url(internet))
        runCurrent()
        assertEquals(false, reachable)
        probe.asked.clear()
        advance(15.seconds)
        assertEquals(2, probe.asked.size, "back to 15 s after a success")
    }

    @Test
    fun thePeriodicCheckMovesBackToAHigherAddress() = runTest {
        probe.answers += mapOf(internet to homeId)
        val source = roaming()
        probe.asked.clear()
        probe.answers[local] = homeId
        advance(4.minutes)
        assertEquals(url(internet), source.current.value)
        advance(1.minutes)
        assertEquals(listOf(local, internet), probe.asked, "the address in use and those above it")
        assertEquals(url(local), source.current.value)
    }

    @Test
    fun onTheFirstAddressThePeriodicCheckAsksOnlyIt() = runTest {
        probe.answers += mapOf(local to homeId, internet to homeId)
        roaming()
        probe.asked.clear()
        advance(5.minutes)
        assertEquals(listOf(local), probe.asked)
    }

    @Test
    fun aDeadFirstAddressIsLeftAtThePeriodicCheck() = runTest {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val source = roaming()
        probe.answers.remove(local)
        advance(5.minutes)
        assertEquals(url(internet), source.current.value)
        assertEquals(true, reachable)
    }

    @Test
    fun aMissKeepsTheLastProvedAddressAndMarksTheServerUnreachable() = runTest {
        probe.answers += mapOf(internet to homeId)
        val source = roaming()
        // A slow but correct server: at the periodic check it does not answer in time.
        probe.answers[internet] = "slow"
        advance(5.minutes)
        assertEquals(url(internet), source.awaitCurrent(), "requests are not held up by the check")
        advance(5.seconds)
        advance(5.seconds)
        assertEquals(url(internet), source.current.value)
        assertEquals(false, reachable)
        probe.answers[internet] = homeId
        advance(15.seconds)
        assertEquals(url(internet), source.current.value)
        assertEquals(true, reachable)
    }

    @Test
    fun aNetworkChangeAsksEveryAddressAgain() = runTest {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val source = roaming()
        probe.answers.remove(local)
        probe.asked.clear()
        changes.emit(NetworkChange.Changed)
        advance(1.seconds)
        assertEquals(setOf(local, internet), probe.asked.toSet())
        assertEquals(url(internet), source.current.value)
    }

    @Test
    fun afterANetworkChangeRequestsWaitInsteadOfGoingToTheOldAddress() = runTest {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val source = roaming()
        // On the new network something slow answers at the old address.
        probe.answers[local] = "slow"
        changes.emit(NetworkChange.Changed)
        runCurrent()
        assertNull(source.current.value)
        val request = async { source.awaitCurrent() }
        runCurrent()
        assertFalse(request.isCompleted)
        advance(1.seconds)
        assertEquals(url(internet), request.await(), "once settled, without waiting for the slow old address")
    }

    @Test
    fun aNetworkChangeWhileNothingAnswersAsksOnceItSettles() = runTest {
        val source = roaming()
        probe.answers[internet] = homeId
        changes.emit(NetworkChange.Changed)
        advance(1.seconds)
        assertEquals(url(internet), source.current.value, "well before the 15 s retry")
    }

    @Test
    fun aWakeLeftFromBeforeANetworkChangeWaitsForTheSettle() = runTest {
        probe.answers += mapOf(local to "slow", internet to "slow")
        roaming()
        // The first check misses at 5 s; the timer's next one runs from 20 s to 25 s.
        advance(21.seconds)
        // A resumed network leaves a wake while that check runs; then the network changes.
        changes.emit(NetworkChange.Resumed)
        changes.emit(NetworkChange.Changed)
        probe.asked.clear()
        runCurrent()
        assertTrue(probe.asked.isEmpty(), "the network has not settled yet")
        advance(1.seconds)
        assertEquals(setOf(local, internet), probe.asked.toSet())
    }

    @Test
    fun aBurstOfNetworkUpdatesMakesOneCheckOnceItSettles() = runTest {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val source = roaming()
        probe.asked.clear()
        changes.emit(NetworkChange.Changed)
        val request = async { source.awaitCurrent() }
        // Ten updates over 1.8 s, as a Mac reports one Wi-Fi transition.
        repeat(9) {
            advance(200.milliseconds)
            changes.emit(NetworkChange.Changed)
        }
        runCurrent()
        assertTrue(probe.asked.isEmpty(), "nothing is asked during the burst")
        assertFalse(request.isCompleted, "requests wait throughout")
        advance(999.milliseconds)
        assertTrue(probe.asked.isEmpty())
        advance(1.milliseconds)
        assertEquals(listOf(local, internet), probe.asked, "one check, 1 s after the last update")
        assertEquals(url(local), request.await())
        advance(10.seconds)
        assertEquals(2, probe.asked.size)
    }

    @Test
    fun aRequestThatFindsNoAddressAsksForACheck() = runTest {
        val source = roaming()
        advance(15.seconds)
        probe.answers[internet] = homeId
        // The timer's next check is 30 s after the one at 15 s; a request 15 s after it asks at once.
        advance(15.seconds)
        assertNull(source.awaitCurrent())
        runCurrent()
        assertEquals(url(internet), source.current.value, "well before the timer's check at 45 s")
    }

    @Test
    fun requestsInARowMakeABoundedNumberOfChecks() = runTest {
        val source = roaming()
        repeat(60) {
            advance(1.seconds)
            source.awaitCurrent()
        }
        // At most one check per 15 s after the last one ended, plus the one at start: 5 checks of 2 addresses, not 60.
        assertTrue(probe.asked.size <= 10, "asked ${probe.asked.size} times")
    }

    @Test
    fun aMissThatARequestWokeDoesNotLengthenTheNextWait() = runTest {
        val source = roaming()
        advance(15.seconds)
        // A timer miss at 15 s: the next wait is 30 s, then 60 s.
        advance(15.seconds)
        source.awaitCurrent()
        runCurrent()
        // The request's miss at 30 s waits the current 60 s and leaves it at 60 s: checks at 90 s and 150 s, not 90 s and 210 s.
        probe.asked.clear()
        advance(60.seconds)
        assertEquals(2, probe.asked.size)
        advance(60.seconds)
        assertEquals(4, probe.asked.size)
    }

    @Test
    fun aResumedNetworkKeepsTheAddressAndAsksAgainAtOnce() = runTest {
        probe.answers += mapOf(local to homeId, internet to "slow")
        val source = roaming()
        probe.asked.clear()
        val before = currentTime
        changes.emit(NetworkChange.Resumed)
        runCurrent()
        // A change would have dropped the address and waited 1 s for the network to settle.
        assertEquals(url(local), source.current.value)
        assertEquals(url(local), source.awaitCurrent(), "requests are not held")
        assertEquals(setOf(local, internet), probe.asked.toSet(), "asked without waiting")
        assertEquals(before, currentTime)
    }

    @Test
    fun aWaitThatSpansAResumedNetworkStartsAgainInsteadOfFailing() = runTest {
        probe.answers += mapOf(local to "hang", internet to "hang")
        val source = roaming()
        val request = async { source.awaitCurrent() }
        // The app comes back to the foreground just before the request's 11 s bound.
        advance(10.seconds)
        changes.emit(NetworkChange.Resumed)
        advance(1.5.seconds)
        assertFalse(request.isCompleted, "the wait started again")
        probe.answers[internet] = homeId
        changes.emit(NetworkChange.Changed)
        advance(1.seconds)
        assertEquals(url(internet), request.await())
    }

    @Test
    fun resumedNetworksRestartAWaitAtMostUpToTwiceTheBound() = runTest {
        probe.answers += mapOf(local to "hang", internet to "hang")
        val source = roaming()
        val request = async { source.awaitCurrent() }
        // Each resumed network comes just before the wait would end; the bound is 11 s, so the cap is 22 s.
        advance(10.seconds)
        changes.emit(NetworkChange.Resumed)
        advance(10.seconds)
        changes.emit(NetworkChange.Resumed)
        advance(1.9.seconds)
        assertFalse(request.isCompleted, "the restarted wait still runs")
        changes.emit(NetworkChange.Resumed)
        advance(0.2.seconds)
        assertTrue(request.isCompleted, "no wait goes beyond twice the bound")
        assertNull(request.await())
    }

    @Test
    fun aResumedNetworkStartsTheRetryWaitsOver() = runTest {
        roaming()
        // Misses at 0, 15 and 45 s; the next wait would be 60 s.
        advance(15.seconds + 30.seconds + 1.seconds)
        changes.emit(NetworkChange.Resumed)
        runCurrent()
        probe.asked.clear()
        advance(15.seconds)
        assertEquals(2, probe.asked.size, "15 s after the check the resumed network made")
    }

    @Test
    fun aRequestDuringACheckMakesNoNewRound() = runTest {
        probe.answers += mapOf(local to "slow", internet to "slow")
        val source = roaming()
        // The first check misses at 5 s; the timer's next one runs from 20 s to 25 s.
        advance(21.seconds)
        probe.asked.clear()
        assertNull(source.awaitCurrent())
        source.awaitCurrent()
        advance(5.seconds)
        assertTrue(probe.asked.isEmpty(), "the running check answered the requests")
        advance(25.seconds)
        assertTrue(probe.asked.isEmpty(), "the next check is the timer's, 30 s after the last")
        advance(5.seconds)
        assertEquals(2, probe.asked.size)
    }

    @Test
    fun aRequestTooSoonAfterACheckIsDeferredToTheEndOfTheWindow() = runTest {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val source = roaming()
        advance(5.seconds)
        probe.answers.remove(local)
        probe.asked.clear()
        source.reportUnreachable(url(local))
        advance(9.seconds)
        assertTrue(probe.asked.isEmpty(), "not before 15 s after the last check")
        advance(1.seconds)
        assertEquals(setOf(local, internet), probe.asked.toSet(), "not after the 5 min periodic check")
        assertEquals(url(internet), source.current.value)
    }

    @Test
    fun anAnswerInTheSameTickAsAnEditIsNotUsed() = runTest {
        probe.answers += mapOf(local to "slow", internet to "late")
        val source = roaming()
        advanceTimeBy(3.seconds)
        // The internet address answers at 3 s; it is removed in the same tick, before its answer is handled.
        source.update(saved.withoutAddress(internet))
        runCurrent()
        assertNull(source.current.value)
        advance(5.seconds)
        assertNull(source.current.value)
        // The source still checks: once the local address answers, it is found.
        probe.answers[local] = homeId
        advance(15.seconds)
        assertEquals(url(local), source.current.value)
    }

    @Test
    fun anEditDuringTheFirstCheckIsCheckedAgain() = runTest {
        probe.answers += mapOf(local to "slow", internet to "slow")
        val source = roaming()
        advance(1.seconds)
        probe.answers[internet] = homeId
        source.update(saved.withoutAddress(local))
        runCurrent()
        assertEquals(url(internet), source.current.value)
    }

    @Test
    fun anEditDuringTheCheckAfterASettledNetworkChangeIsCheckedAgain() = runTest {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val source = roaming()
        probe.answers += mapOf(local to "slow", internet to "slow")
        changes.emit(NetworkChange.Changed)
        advance(1.seconds)
        // The settle has started the check; it is still waiting for the slow answers.
        advance(500.milliseconds)
        probe.answers[internet] = homeId
        source.update(saved.withoutAddress(local))
        runCurrent()
        assertEquals(url(internet), source.current.value)
    }

    @Test
    fun aFailedRequestMovesToALowerAddressOnlyWhenTheCheckEnds() = runTest {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val source = roaming()
        waitOutTheRequestLimit()
        probe.answers[local] = "slow"
        source.reportUnreachable(url(local))
        runCurrent()
        assertEquals(url(local), source.current.value, "a lower address does not replace the one in use mid-check")
        advance(5.seconds)
        assertEquals(url(internet), source.current.value)
    }

    @Test
    fun anEditThatRemovesTheAddressUsedMeanwhileDropsIt() = runTest {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val source = roaming()
        probe.answers[local] = "late"
        changes.emit(NetworkChange.Changed)
        advance(1.seconds)
        assertEquals(url(internet), source.current.value, "used while the local address has not answered")
        source.update(saved.withoutAddress(internet))
        assertNull(source.awaitCurrent())
        advance(3.seconds)
        assertEquals(url(local), source.current.value)
    }

    @Test
    fun aNetworkChangeStartsTheRetryWaitsOver() = runTest {
        roaming()
        advance(15.seconds + 30.seconds)
        probe.asked.clear()
        // The next wait would be 60 s; after a network change it is 15 s again.
        changes.emit(NetworkChange.Changed)
        advance(1.seconds)
        assertEquals(2, probe.asked.size)
        advance(15.seconds)
        assertEquals(4, probe.asked.size)
    }

    @Test
    fun aFailedRequestToTheAddressInUseMovesOn() = runTest {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val source = roaming()
        probe.answers.remove(local)
        waitOutTheRequestLimit()
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
    fun theAddressInUseStaysWhileACheckForAFailedRequestRuns() = runTest {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val source = roaming()
        probe.answers += mapOf(local to "slow", internet to "slow")
        waitOutTheRequestLimit()
        source.reportUnreachable(url(local))
        runCurrent()
        assertEquals(url(local), source.current.value)
        assertEquals(url(local), source.awaitCurrent())
    }

    @Test
    fun severalWakesDuringOneCheckMakeExactlyOneMore() = runTest {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val source = roaming()
        probe.answers += mapOf(local to "slow", internet to "late")
        probe.asked.clear()
        changes.emit(NetworkChange.Resumed)
        runCurrent()
        repeat(3) { changes.emit(NetworkChange.Resumed) }
        advance(5.seconds)
        assertEquals(url(internet), source.current.value)
        advance(5.seconds)
        assertEquals(4, probe.asked.size, "two checks of two addresses")
        advance(1.minutes)
        assertEquals(4, probe.asked.size)
    }

    @Test
    fun aCheckWhoseAddressesWereEditedMeanwhileIsCancelled() = runTest {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val source = roaming()
        probe.answers += mapOf(local to "slow", internet to "late")
        waitOutTheRequestLimit()
        source.reportUnreachable(url(local))
        runCurrent()
        probe.asked.clear()
        // The running check would choose the internet address, which is removed before it finishes.
        source.update(saved.withoutAddress(internet))
        runCurrent()
        assertEquals(listOf(local), probe.asked, "a new check starts at once")
        advance(5.seconds)
        assertEquals(url(local), source.current.value, "a miss keeps the address in use")
        assertEquals(false, reachable)
    }

    @Test
    fun aRemovedAddressIsDroppedAtOnce() = runTest {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val source = roaming()
        probe.answers[internet] = "slow"
        source.update(saved.withoutAddress(local))
        assertNull(source.awaitCurrent(), "requests no longer go to the removed address, before any check")
        runCurrent()
        assertNotEquals(url(local), source.current.value)
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
        assertEquals(url(local), source.current.value)
    }

    @Test
    fun aStoppedSourceHasNoAddress() = runTest {
        probe.answers += mapOf(local to homeId)
        val source = RoamingServerAddress(saved, probe, capabilities, RoamingTiming(), testTimeSource)
        val job = backgroundScope.launch { source.run(changes) }
        runCurrent()
        assertEquals(url(local), source.current.value)
        job.cancel()
        runCurrent()
        assertNull(source.current.value)
        assertNull(source.awaitCurrent())
    }
}
