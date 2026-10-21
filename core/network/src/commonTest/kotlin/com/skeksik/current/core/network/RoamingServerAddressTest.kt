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
     * Answers per address: a server id; "soon" (the saved server, after 0.3 s); "late" (the saved server, after 3 s); "slow" (times
     * out after 5 s, as Jellyfin's probe does); "dead" (unreachable after 5 s, as a connect timeout on Android
     * may report it); "hang" (never answers); anything else is unreachable at once.
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
                "dead" -> {
                    delay(5.seconds)
                    throw ServiceException(server, ServiceErrorKind.Unreachable)
                }
                "hang" -> awaitCancellation()
                "soon" -> {
                    delay(300.milliseconds)
                    ServerIdentity(homeId, "Home", "12.2.0")
                }
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
    private fun TestScope.roaming(
        server: SavedServer = saved,
        timing: RoamingTiming = RoamingTiming(),
        access: LocalNetworkAccess = LocalNetworkAccess.NotNeeded,
    ): RoamingServerAddress {
        val source = RoamingServerAddress(server, probe, capabilities, timing, testTimeSource, access)
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
    fun aRequestThatSucceedsAfterAMissMarksTheServerReachableWithoutProvingAnything() = runTest {
        probe.answers += mapOf(internet to homeId)
        val source = roaming()
        probe.answers[internet] = "slow"
        advance(5.minutes)
        advance(5.seconds)
        advance(5.seconds)
        assertEquals(false, reachable)
        probe.asked.clear()
        // A request to another address proves nothing and changes nothing.
        source.reportReachable(url(local))
        runCurrent()
        assertEquals(false, reachable)
        // A request to the kept address answered while the probe timed out: the server is not offline.
        source.reportReachable(url(internet))
        runCurrent()
        assertEquals(true, reachable)
        assertEquals(url(internet), source.current.value)
        assertTrue(probe.asked.isEmpty(), "a success starts no check")
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
        // A bound shorter than the check's 10 s deadline, so the wait ends while the check still runs.
        val source = roaming(timing = RoamingTiming(requestWaitsAtMost = 4.seconds))
        val request = async { source.awaitCurrent() }
        // The app comes back to the foreground just before the request's bound.
        advance(3.seconds)
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
        val source = roaming(timing = RoamingTiming(requestWaitsAtMost = 4.seconds))
        val request = async { source.awaitCurrent() }
        // Each resumed network comes just before the wait would end; the bound is 4 s, so the cap is 8 s.
        advance(3.seconds)
        changes.emit(NetworkChange.Resumed)
        advance(4.seconds)
        changes.emit(NetworkChange.Resumed)
        advance(0.9.seconds)
        assertFalse(request.isCompleted, "the restarted wait still runs")
        changes.emit(NetworkChange.Resumed)
        advance(0.2.seconds)
        assertTrue(request.isCompleted, "no wait goes beyond twice the bound")
        assertNull(request.await())
    }

    @Test
    fun aCheckWhoseProbesNeverAnswerEndsAfterTwiceTheProbeTimeout() = runTest {
        probe.answers += mapOf(local to "hang", internet to "hang")
        roaming()
        advance(9.seconds)
        assertEquals(null, reachable, "still checking")
        advance(1.seconds)
        assertEquals(false, reachable, "a miss")
        probe.asked.clear()
        advance(15.seconds)
        assertEquals(2, probe.asked.size, "the retry waits go on")
    }

    @Test
    fun aCheckPastItsDeadlineEndsWithTheBestAddressProvedSoFar() = runTest {
        probe.answers += mapOf(local to "hang", internet to homeId)
        val source = roaming()
        assertEquals(url(internet), source.current.value)
        advance(10.seconds)
        probe.asked.clear()
        advance(5.minutes)
        assertEquals(listOf(local, internet), probe.asked, "the periodic check of the address in use and the one above it")
    }

    @Test
    fun aRequestWhoseWaitRunsOutWhileSuspendedSucceedsOnTheReturn() = runTest {
        probe.answers[local] = homeId
        val source = roaming()
        assertEquals(url(local), source.current.value)
        changes.emit(NetworkChange.Suspended)
        runCurrent()
        val request = async { source.awaitCurrent() }
        // The 11 s wait runs out during a 16 s suspension; on a device its timeout fires at the return, before the loop sees it.
        advance(16.seconds)
        assertFalse(request.isCompleted, "it waits for the return instead of failing")
        changes.emit(NetworkChange.Resumed)
        runCurrent()
        assertEquals(url(local), request.await())
    }

    @Test
    fun requestsWaitWithoutALimitWhileSuspendedEvenIfTheProcessKeepsRunning() = runTest {
        probe.answers[local] = homeId
        val source = roaming()
        // A network change leaves a request waiting when Android blocks the network; the process is not frozen for a long time after.
        probe.answers.clear()
        changes.emit(NetworkChange.Changed)
        val before = async { source.awaitCurrent() }
        advance(0.5.seconds)
        changes.emit(NetworkChange.Suspended)
        runCurrent()
        val after = async { source.awaitCurrent() }
        advance(30.seconds)
        val late = async { source.awaitCurrent() }
        advance(70.seconds)
        assertFalse(before.isCompleted || after.isCompleted || late.isCompleted, "nothing fails while suspended, however long")
        probe.answers[internet] = homeId
        changes.emit(NetworkChange.Resumed)
        runCurrent()
        assertEquals(url(internet), before.await())
        assertEquals(url(internet), after.await())
        assertEquals(url(internet), late.await())
    }

    @Test
    fun afterASuspensionNoRequestGoesToTheKeptAddressUntilItIsProvedAgain() = runTest {
        probe.answers[local] = homeId
        val source = roaming()
        assertEquals(url(local), source.current.value)
        changes.emit(NetworkChange.Suspended)
        runCurrent()
        val early = async { source.awaitCurrent() }
        runCurrent()
        assertFalse(early.isCompleted, "a request made in the background waits")
        // Meanwhile the device moved: another server answers at the local address, and the internet one is home.
        probe.answers[local] = otherId
        probe.answers[internet] = homeId
        probe.asked.clear()
        changes.emit(NetworkChange.Resumed)
        val late = async { source.awaitCurrent() }
        runCurrent()
        assertEquals(listOf(local, internet), probe.asked, "checked at once on the return")
        assertEquals(url(internet), early.await())
        assertEquals(url(internet), late.await())
    }

    @Test
    fun aKeptAddressIsShownButNotProvedUntilItIsCheckedAgain() = runTest {
        probe.answers[local] = homeId
        val source = roaming()
        assertEquals(url(local), source.provedNow())
        changes.emit(NetworkChange.Suspended)
        runCurrent()
        assertEquals(url(local), source.current.value)
        assertNull(source.provedNow(), "kept across the suspension, not proved since")
        changes.emit(NetworkChange.Resumed)
        runCurrent()
        assertEquals(url(local), source.provedNow())

        probe.answers[local] = "slow"
        changes.emit(NetworkChange.Changed)
        runCurrent()
        assertNull(source.provedNow())
    }

    @Test
    fun aKeptAddressThatNothingProvesAfterASuspensionIsDropped() = runTest {
        probe.answers[local] = homeId
        val source = roaming()
        changes.emit(NetworkChange.Suspended)
        runCurrent()
        probe.answers.clear()
        changes.emit(NetworkChange.Resumed)
        runCurrent()
        assertNull(source.awaitCurrent(), "the network may have changed while suspended")
        assertNull(source.current.value)
    }

    @Test
    fun nothingIsCheckedWhileSuspended() = runTest {
        probe.answers[local] = homeId
        val source = roaming()
        changes.emit(NetworkChange.Suspended)
        runCurrent()
        probe.asked.clear()
        source.reportUnreachable(url(local))
        advance(10.minutes)
        assertTrue(probe.asked.isEmpty(), "no timer, request or change checks in the background")
    }

    @Test
    fun aResumedNetworkWithNoAddressInUseHoldsRequestsForItsCheck() = runTest {
        probe.answers[local] = homeId
        val source = roaming()
        // A switch while the app's network was blocked: the check after it finds nothing, and the address is gone.
        probe.answers.clear()
        changes.emit(NetworkChange.Changed)
        advance(1.seconds)
        assertNull(source.current.value)
        // Unblocked: the local address answers again, a moment later.
        probe.answers[local] = "late"
        changes.emit(NetworkChange.Resumed)
        val request = async { source.awaitCurrent() }
        runCurrent()
        assertFalse(request.isCompleted, "it waits for the check instead of failing at once")
        advance(3.seconds)
        assertEquals(url(local), request.await())
    }

    @Test
    fun aResumedNetworkDuringACheckWithNoAddressHoldsUntilTheCheckItAskedFor() = runTest {
        val source = roaming()
        // The timer's check at 15 s runs until 20 s, and nothing answers, when the network is resumed at 17 s.
        probe.answers += mapOf(local to "slow", internet to "slow")
        advance(17.seconds)
        probe.answers.clear()
        probe.answers[local] = "late"
        changes.emit(NetworkChange.Resumed)
        val request = async { source.awaitCurrent() }
        runCurrent()
        assertFalse(request.isCompleted)
        advance(3.seconds)
        assertFalse(request.isCompleted, "the running check missed; the one the resume asked for is still running")
        advance(3.seconds)
        assertEquals(url(local), request.await())
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
    fun aFailedAddressInUseGivesWayToALowerOneAsSoonAsItAnswers() = runTest {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val source = roaming()
        waitOutTheRequestLimit()
        // A hotspot switch: the local address now hangs, the internet one answers after 0.3 s.
        probe.answers += mapOf(local to "hang", internet to "soon")
        source.reportUnreachable(url(local))
        advance(299.milliseconds)
        assertEquals(url(local), source.current.value, "until a lower address proves itself")
        advance(1.milliseconds)
        assertEquals(url(internet), source.current.value, "not when the check expires at 10 s")
        assertEquals(true, reachable)
    }

    @Test
    fun aFailedAddressThatAnswersAfterAllIsMovedBackTo() = runTest {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val source = roaming()
        waitOutTheRequestLimit()
        probe.answers += mapOf(local to "late", internet to "soon")
        source.reportUnreachable(url(local))
        advance(300.milliseconds)
        assertEquals(url(internet), source.current.value)
        advance(2700.milliseconds)
        assertEquals(url(local), source.current.value, "it ranks higher")
        probe.asked.clear()
        advance(5.minutes)
        assertEquals(listOf(local), probe.asked, "it is proved again: the periodic check asks only it")
    }

    @Test
    fun aFailureDeferredByTheRequestLimitStillLetsALowerAddressTakeOverAtOnce() = runTest {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val source = roaming()
        advance(5.seconds)
        probe.answers += mapOf(local to "hang", internet to "soon")
        source.reportUnreachable(url(local))
        advance(10.seconds)
        assertEquals(url(local), source.current.value, "the check comes 15 s after the last")
        advance(300.milliseconds)
        assertEquals(url(internet), source.current.value)
    }

    @Test
    fun aFailureDuringACheckLetsALowerAddressTakeOverAtOnce() = runTest {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val source = roaming()
        // The periodic check asks only the local address, which now hangs; the failure comes while it runs.
        probe.answers += mapOf(local to "hang", internet to "soon")
        advance(5.minutes + 1.seconds)
        source.reportUnreachable(url(local))
        runCurrent()
        assertEquals(url(local), source.current.value)
        // At 10 s the partial check expires, and the full check that follows still treats the local address as failed.
        advance(9.seconds + 300.milliseconds)
        assertEquals(url(internet), source.current.value, "not when the full check ends, 10 s later")
    }

    @Test
    fun aFailureAfterALowerAddressProvedItselfInTheCheckSwitchesAtOnce() = runTest {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val source = roaming()
        probe.answers += mapOf(local to "hang", internet to "soon")
        changes.emit(NetworkChange.Resumed)
        advance(1.seconds)
        assertEquals(url(local), source.current.value)
        source.reportUnreachable(url(local))
        runCurrent()
        assertEquals(url(internet), source.current.value, "the internet address proved itself at 0.3 s, in this check")
    }

    @Test
    fun aFailureDuringAFullCheckSwitchesWhenALowerAddressAnswersLater() = runTest {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val source = roaming()
        probe.answers += mapOf(local to "hang", internet to "late")
        changes.emit(NetworkChange.Resumed)
        advance(1.seconds)
        source.reportUnreachable(url(local))
        advance(1999.milliseconds)
        assertEquals(url(local), source.current.value)
        advance(1.milliseconds)
        assertEquals(url(internet), source.current.value, "at 3 s, not when the check expires at 10 s")
    }

    @Test
    fun aFailedAddressThatAnswersIsNoLongerSuspectInTheCheckAnEditStarts() = runTest {
        probe.answers += mapOf(internet to homeId, lan to homeId)
        val source = roaming(saved.withAddress(ServerAddress(lan, AddressScope.Local)))
        assertEquals(url(internet), source.current.value)
        waitOutTheRequestLimit()
        probe.answers += mapOf(local to "hang", internet to "soon", lan to "late")
        source.reportUnreachable(url(internet))
        advance(300.milliseconds)
        assertEquals(url(internet), source.current.value, "it answered, so it is proved again")
        // Now only the lower address answers at once; the edit replaces the running check.
        probe.answers += mapOf(internet to "hang", lan to homeId)
        source.update(saved.withAddress(ServerAddress(lan, AddressScope.Local)).withoutAddress(local))
        runCurrent()
        assertEquals(url(internet), source.current.value, "a healthy address is not swapped down mid-check")
    }

    @Test
    fun thePeriodicCheckNeverSwapsDownMidCheck() = runTest {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val source = roaming()
        // Nothing reported: the local address simply stops answering before the periodic check.
        probe.answers += mapOf(local to "hang", internet to "soon")
        advance(5.minutes)
        // The partial check expires at 10 s; the full check proves the internet address at 10.3 s and ends at 20 s.
        advance(10.seconds + 300.milliseconds)
        assertEquals(url(local), source.current.value, "a lower address waits for the check to end")
        advance(9.seconds + 700.milliseconds)
        assertEquals(url(internet), source.current.value)
    }

    @Test
    fun aResumedNetworkNeverSwapsDownMidCheck() = runTest {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val source = roaming()
        probe.answers += mapOf(local to "hang", internet to "soon")
        changes.emit(NetworkChange.Resumed)
        advance(300.milliseconds)
        assertEquals(url(local), source.current.value, "the address in use was not reported")
        advance(9.seconds + 700.milliseconds)
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
    fun aFailedRequestMovesToALowerAddressBeforeTheFailingOneTimesOut() = runTest {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val source = roaming()
        waitOutTheRequestLimit()
        probe.answers[local] = "slow"
        source.reportUnreachable(url(local))
        runCurrent()
        assertEquals(url(internet), source.current.value, "the failing address is not proved in this check")
        advance(5.seconds)
        assertEquals(url(internet), source.current.value)
    }

    @Test
    fun aFailureCarriesOverToTheCheckAnEditStarts() = runTest {
        probe.answers += mapOf(local to homeId, lan to homeId, internet to homeId)
        val source = roaming(saved.withAddress(ServerAddress(lan, AddressScope.Local)))
        waitOutTheRequestLimit()
        probe.answers += mapOf(local to "hang", lan to "hang", internet to "soon")
        source.reportUnreachable(url(local))
        runCurrent()
        // The running check is cancelled by the edit before the internet address answers.
        source.update(saved)
        advance(300.milliseconds)
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

    // Local network access (ADR 0019, ADR 0020).

    /** An internet name that resolves to a local address on this network (split DNS), and a denied platform. */
    private val splitName = "https://jellyfin.example.net"
    private val denied get() = FakeLocalNetworkAccess(LocalNetworkPermission.CanAsk)

    private fun server(vararg addresses: String) = saved.copy(
        addresses = addresses.map { ServerAddress(it, url(it).scope) },
    )

    @Test
    fun whileLocalAccessIsDeniedLocalAddressesAreNotAsked() = runTest {
        for (permission in listOf(LocalNetworkPermission.CanAsk, LocalNetworkPermission.Blocked)) {
            probe.asked.clear()
            probe.answers += mapOf(local to "slow", lan to "slow", internet to "late")
            val source = roaming(server(local, lan, internet), access = FakeLocalNetworkAccess(permission))
            advance(3.seconds)
            assertEquals(url(internet), source.current.value)
            assertEquals(listOf(internet), probe.asked, "$permission")
            assertEquals(true, reachable)
        }
    }

    @Test
    fun whileDeniedAServerWithOnlyLocalAddressesFailsAtOnce() = runTest {
        probe.answers += mapOf(local to homeId, lan to homeId)
        val source = roaming(server(local, lan), access = denied)
        val started = currentTime
        assertNull(source.awaitCurrent(), "not after the 11 s bound")
        assertEquals(started, currentTime)
        assertEquals(false, reachable)
        assertTrue(probe.asked.isEmpty())
    }

    @Test
    fun aGrantAsksAgainAtOnceAndLocalIsUsedAgain() = runTest {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val access = denied
        val source = roaming(access = access)
        assertEquals(url(internet), source.current.value)
        val before = currentTime
        access.set(LocalNetworkPermission.Granted)
        runCurrent()
        assertEquals(url(local), source.current.value)
        assertEquals(before, currentTime)
        assertEquals(listOf(internet, local, internet), probe.asked)
    }

    @Test
    fun aGrantWithOnlyLocalAddressesHoldsRequestsForItsCheck() = runTest {
        probe.answers += mapOf(local to "soon")
        val access = denied
        val source = roaming(server(local), access = access)
        assertNull(source.current.value)
        access.set(LocalNetworkPermission.Granted)
        runCurrent()
        val request = async { source.awaitCurrent() }
        advance(300.milliseconds)
        assertEquals(url(local), request.await(), "the request waits for the check the grant started instead of failing")
    }

    @Test
    fun aSignalWithNothingChangedAsksNothing() = runTest {
        probe.answers += mapOf(internet to homeId)
        val access = denied
        roaming(access = access)
        access.set(LocalNetworkPermission.Blocked)
        runCurrent()
        access.changes.tryEmit(Unit)
        runCurrent()
        assertEquals(listOf(internet), probe.asked)
    }

    @Test
    fun aGrantWithoutASignalIsSeenByTheNextCheck() = runTest {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val access = denied
        val source = roaming(access = access)
        access.permission = LocalNetworkPermission.Granted
        advance(5.minutes)
        assertEquals(url(local), source.current.value)
    }

    @Test
    fun aDenialDropsALocalAddressInUse() = runTest {
        probe.answers += mapOf(local to homeId, internet to "soon")
        val access = FakeLocalNetworkAccess(LocalNetworkPermission.Granted)
        val source = roaming(access = access)
        advance(300.milliseconds)
        assertEquals(url(local), source.current.value)
        probe.asked.clear()
        access.set(LocalNetworkPermission.CanAsk)
        runCurrent()
        assertNull(source.current.value, "nothing at a local address can answer now")
        val request = async { source.awaitCurrent() }
        advance(300.milliseconds)
        assertEquals(url(internet), request.await())
        assertEquals(listOf(internet), probe.asked)
    }

    @Test
    fun aDenialMidCheckRestartsItWithoutTheLocalAddresses() = runTest {
        probe.answers += mapOf(local to "slow", internet to "slow")
        val access = FakeLocalNetworkAccess(LocalNetworkPermission.Granted)
        roaming(access = access)
        probe.answers[internet] = homeId
        access.set(LocalNetworkPermission.CanAsk)
        runCurrent()
        assertEquals(listOf(local, internet, internet), probe.asked)
        assertEquals(true, reachable, "the check ended without waiting for the local probe")
    }

    /** A request finds the internet address in use failing: once the request limit allows, a full check asks every address. */
    private fun TestScope.failInternet(source: RoamingServerAddress) {
        waitOutTheRequestLimit()
        probe.asked.clear()
        source.reportUnreachable(url(internet))
        runCurrent()
    }

    @Test
    fun anInternetNameThatTimesOutWhileDeniedIsSkippedUntilTheNetworkChanges() = runTest {
        probe.answers += mapOf(splitName to "slow", internet to homeId)
        val source = roaming(server(splitName, internet), access = denied)
        assertEquals(url(internet), source.current.value)
        advance(5.seconds)
        failInternet(source)
        assertEquals(listOf(internet), probe.asked, "the full check skips the name that timed out")
        changes.emit(NetworkChange.Changed)
        advance(1.seconds)
        assertEquals(listOf(internet, splitName, internet), probe.asked, "on another network it may resolve to a public address")
    }

    @Test
    fun anInternetNameUnreachableAfterTheWholeTimeoutWhileDeniedIsSkippedToo() = runTest {
        probe.answers += mapOf(splitName to "dead", internet to homeId)
        val source = roaming(server(splitName, internet), access = denied)
        advance(5.seconds)
        failInternet(source)
        assertEquals(listOf(internet), probe.asked)
    }

    @Test
    fun aGrantForgetsTheNamesThatTimedOut() = runTest {
        probe.answers += mapOf(splitName to "slow", internet to homeId)
        val access = denied
        roaming(server(splitName, internet), access = access)
        advance(5.seconds)
        access.set(LocalNetworkPermission.Granted)
        advance(5.seconds)
        probe.asked.clear()
        access.set(LocalNetworkPermission.CanAsk)
        runCurrent()
        assertEquals(listOf(splitName, internet), probe.asked, "the mark from before the grant is gone")
    }

    @Test
    fun anInternetNameThatIsRefusedWhileDeniedIsStillAsked() = runTest {
        probe.answers += mapOf(internet to homeId)
        val source = roaming(server(splitName, internet), access = denied)
        failInternet(source)
        assertEquals(listOf(splitName, internet), probe.asked, "a server that is down is not hidden")
    }

    @Test
    fun anInternetNameThatTimesOutWhileAllowedIsStillAsked() = runTest {
        probe.answers += mapOf(splitName to "slow", internet to homeId)
        val source = roaming(server(splitName, internet), access = FakeLocalNetworkAccess(LocalNetworkPermission.Granted))
        advance(5.seconds)
        failInternet(source)
        assertEquals(listOf(splitName, internet), probe.asked)
    }

    @Test
    fun anInternetOnlyServerThatIsSlowAtFirstIsProvedByTheRetry() = runTest {
        probe.answers += mapOf(internet to "slow")
        val source = roaming(server(internet), access = denied)
        advance(5.seconds)
        assertNull(source.current.value)
        probe.answers[internet] = homeId
        advance(15.seconds)
        assertEquals(url(internet), source.current.value, "a mark never leaves a check with nothing to ask")
        assertEquals(true, reachable)
    }

    @Test
    fun aMarkedHigherAddressIsAskedAgainByThePeriodicCheckAndMovedBackTo() = runTest {
        probe.answers += mapOf(splitName to "slow", internet to homeId)
        val source = roaming(server(splitName, internet), access = denied)
        advance(5.seconds)
        probe.answers[splitName] = homeId
        advance(5.minutes)
        assertEquals(url(splitName), source.current.value, "the server was only down")
    }

    @Test
    fun aNameThatProvedOnThisNetworkIsNeverMarked() = runTest {
        probe.answers += mapOf(splitName to homeId, internet to homeId)
        val source = roaming(server(splitName, internet), access = denied)
        assertEquals(url(splitName), source.current.value)
        waitOutTheRequestLimit()
        probe.answers[splitName] = "slow"
        source.reportUnreachable(url(splitName))
        runCurrent()
        assertEquals(url(internet), source.current.value)
        advance(5.seconds)
        failInternet(source)
        assertEquals(listOf(splitName, internet), probe.asked, "a name that answered here does not resolve locally")
    }

    @Test
    fun anOverlayVpnAddressIsStillAskedWhileDenied() = runTest {
        val overlay = "http://100.100.1.2:8096"
        val overlayName = "https://media.tailnet-name.ts.net"
        probe.answers += mapOf(overlay to "late", overlayName to homeId, local to homeId)
        val source = roaming(server(overlay, overlayName, local), access = denied)
        assertEquals(listOf(overlay, overlayName), probe.asked)
        advance(3.seconds)
        assertEquals(url(overlay), source.current.value)
    }

    @Test
    fun aChangeOfAccessDuringASettleWaitsForIt() = runTest {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val access = denied
        val source = roaming(access = access)
        changes.emit(NetworkChange.Changed)
        runCurrent()
        probe.asked.clear()
        access.set(LocalNetworkPermission.Granted)
        runCurrent()
        assertTrue(probe.asked.isEmpty(), "no check before the network settles")
        advance(1.seconds)
        assertEquals(listOf(local, internet), probe.asked)
        assertEquals(url(local), source.current.value)
    }

    @Test
    fun aChangeOfAccessWhileSuspendedWaitsForTheReturn() = runTest {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val access = denied
        val source = roaming(access = access)
        changes.emit(NetworkChange.Suspended)
        runCurrent()
        probe.asked.clear()
        access.set(LocalNetworkPermission.Granted)
        runCurrent()
        assertTrue(probe.asked.isEmpty(), "nothing runs in the background")
        changes.emit(NetworkChange.Resumed)
        runCurrent()
        assertEquals(listOf(local, internet), probe.asked)
        assertEquals(url(local), source.current.value)
    }

    @Test
    fun aDenialWithoutASignalDropsALocalAddressInUseAtTheNextCheck() = runTest {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val access = FakeLocalNetworkAccess(LocalNetworkPermission.Granted)
        val source = roaming(access = access)
        assertEquals(url(local), source.current.value)
        access.permission = LocalNetworkPermission.CanAsk
        probe.asked.clear()
        advance(5.minutes)
        assertEquals(url(internet), source.current.value)
        assertEquals(listOf(internet), probe.asked)
    }

    @Test
    fun theRetryWhileNothingIsInUseAsksMarkedNamesToo() = runTest {
        val backup = "https://backup.example.com"
        probe.answers += mapOf(internet to "slow")
        val source = roaming(server(internet, backup), access = denied)
        advance(5.seconds)
        assertNull(source.current.value, "the first timed out and is marked; the second was refused")
        probe.answers[internet] = homeId
        advance(15.seconds)
        assertEquals(url(internet), source.current.value, "found by the retry, not only after a resume or network change")
    }

    /** Collects [source]'s switches from now on. */
    private fun TestScope.switchesOf(source: ServerAddressSource): List<ServerUrl> {
        val seen = mutableListOf<ServerUrl>()
        backgroundScope.launch { source.switches.collect { seen += it } }
        runCurrent()
        return seen
    }

    @Test
    fun checkingIsTrueWhileACheckRuns() = runTest {
        probe.answers += mapOf(local to "slow", internet to homeId)
        val source = roaming()
        assertEquals(url(internet), source.current.value)
        assertTrue(source.checking.value, "the local address is still being asked")
        advance(5.seconds)
        assertFalse(source.checking.value)
    }

    @Test
    fun checkingIsTrueWhileANetworkChangeSettles() = runTest {
        probe.answers += mapOf(local to homeId)
        val source = roaming()
        assertFalse(source.checking.value)
        changes.emit(NetworkChange.Changed)
        runCurrent()
        assertTrue(source.checking.value)
        advance(1.seconds)
        assertFalse(source.checking.value, "the check after the settle proved the local address at once")
    }

    @Test
    fun refreshStartsAFullCheck() = runTest {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val source = roaming()
        probe.asked.clear()
        source.refresh()
        runCurrent()
        assertEquals(listOf(local, internet), probe.asked)
        assertEquals(url(local), source.current.value)
    }

    @Test
    fun refreshIsIgnoredWhileAFullCheckRuns() = runTest {
        probe.answers += mapOf(local to "slow", internet to homeId)
        val source = roaming()
        probe.asked.clear()
        source.refresh()
        source.refresh()
        runCurrent()
        assertEquals(emptyList(), probe.asked)
        advance(5.seconds)
        assertEquals(emptyList(), probe.asked, "no check follows the one that answered it")
    }

    @Test
    fun refreshIsIgnoredWhileANetworkChangeSettles() = runTest {
        probe.answers += mapOf(local to homeId)
        val source = roaming()
        changes.emit(NetworkChange.Changed)
        runCurrent()
        probe.asked.clear()
        source.refresh()
        runCurrent()
        assertEquals(emptyList(), probe.asked)
        advance(1.seconds)
        assertEquals(listOf(local, internet), probe.asked, "only the check after the settle")
    }

    @Test
    fun refreshReplacesAPeriodicCheckWithAFullOne() = runTest {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val source = roaming()
        probe.answers[local] = "late"
        probe.asked.clear()
        advance(5.minutes)
        assertEquals(listOf(local), probe.asked, "the periodic check asks the address in use and those above it")
        source.refresh()
        runCurrent()
        assertEquals(listOf(local, local, internet), probe.asked)
        advance(3.seconds)
        assertEquals(url(local), source.current.value)
        assertFalse(source.checking.value)
    }

    @Test
    fun noAddressDuringTheFirstCheckIsASwitch() = runTest {
        probe.answers += mapOf(local to "soon", internet to homeId)
        val source = roaming()
        val switches = switchesOf(source)
        assertEquals(url(internet), source.current.value)
        advance(1.seconds)
        assertEquals(url(local), source.current.value, "moved up within the first check")
        assertEquals(emptyList(), switches)
    }

    @Test
    fun anotherAddressAfterANetworkChangeIsASwitch() = runTest {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val source = roaming()
        val switches = switchesOf(source)
        probe.answers -= local
        changes.emit(NetworkChange.Changed)
        advance(1.seconds)
        assertEquals(url(internet), source.current.value)
        assertEquals(listOf(url(internet)), switches)
        changes.emit(NetworkChange.Changed)
        advance(1.seconds)
        assertEquals(listOf(url(internet)), switches, "the same address again is not a switch")
    }

    @Test
    fun anotherAddressAfterAFailedRequestIsASwitch() = runTest {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val source = roaming()
        val switches = switchesOf(source)
        waitOutTheRequestLimit()
        probe.answers -= local
        source.reportUnreachable(url(local))
        runCurrent()
        assertEquals(url(internet), source.current.value)
        assertEquals(listOf(url(internet)), switches)
    }

    @Test
    fun anEditIsNeverASwitch() = runTest {
        probe.answers += mapOf(local to homeId, internet to homeId)
        val source = roaming()
        val switches = switchesOf(source)
        source.update(saved.copy(addresses = saved.addresses.reversed()))
        runCurrent()
        assertEquals(url(internet), source.current.value, "moved to the new first address")
        source.update(saved.copy(addresses = saved.addresses.take(1)))
        runCurrent()
        assertEquals(url(local), source.current.value, "the address in use was removed")
        assertEquals(emptyList(), switches)
    }
}
