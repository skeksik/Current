package com.skeksik.current.core.network

import com.skeksik.current.core.database.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class LocalNetworkRequestTest {
    private class CountingRequester(private val answer: PermissionAnswer?) : LocalNetworkRequester {
        var asked = 0

        override suspend fun request(): PermissionAnswer? {
            asked++
            return answer
        }
    }

    @Test
    fun grantedOrUnsupportedNeverAsks() = runBlocking {
        val requester = CountingRequester(PermissionAnswer(granted = false))
        val state = LocalNetworkPermissionState(InMemoryKeyValueStore())
        assertEquals(LocalNetworkPermission.NotNeeded, state.request(apiSupported = false, granted = false, requester))
        assertEquals(LocalNetworkPermission.Granted, state.request(apiSupported = true, granted = true, requester))
        assertEquals(0, requester.asked)
    }

    @Test
    fun canAskAndBlockedAsk() = runBlocking {
        val requester = CountingRequester(PermissionAnswer(granted = false))
        val state = LocalNetworkPermissionState(InMemoryKeyValueStore())
        assertEquals(LocalNetworkPermission.CanAsk, state.request(apiSupported = true, granted = false, requester))
        assertEquals(LocalNetworkPermission.Blocked, state.request(apiSupported = true, granted = false, requester))
        // Blocked still asks: Android answers at once, or shows the dialog if it reset its denials.
        assertEquals(LocalNetworkPermission.Blocked, state.request(apiSupported = true, granted = false, requester))
        assertEquals(3, requester.asked)
    }

    @Test
    fun noAnswerChangesNothing() = runBlocking {
        val state = LocalNetworkPermissionState(InMemoryKeyValueStore())
        repeat(3) { assertEquals(LocalNetworkPermission.CanAsk, state.request(apiSupported = true, granted = false, CountingRequester(null))) }
    }

    @Test
    fun withoutAnActivityTheBridgeAnswersAtOnce() = runBlocking {
        assertNull(LocalNetworkRequestBridge().request())
    }

    @Test
    fun theBridgeLaunchesOnceAndHandsBackTheAnswer() = runBlocking {
        val bridge = LocalNetworkRequestBridge()
        var launched = 0
        bridge.attach { launched++ }
        val first = async(start = CoroutineStart.UNDISPATCHED) { bridge.request() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { bridge.request() }
        val answer = PermissionAnswer(granted = true)
        bridge.onResult(answer)
        assertEquals(answer, first.await())
        assertEquals(answer, second.await())
        assertEquals(1, launched)
    }

    @Test
    fun aCancelledFirstCallerLeavesTheRequestToTheNextOne() = runBlocking {
        val bridge = LocalNetworkRequestBridge()
        var launched = 0
        bridge.attach { launched++ }
        val first = async(start = CoroutineStart.UNDISPATCHED) { bridge.request() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { bridge.request() }
        first.cancelAndJoin()
        val answer = PermissionAnswer(granted = true)
        bridge.onResult(answer)
        assertEquals(answer, withTimeout(1.seconds) { second.await() })
        assertEquals(1, launched)
    }

    @Test
    fun whenEveryCallerLeavesTheNextRequestShowsTheDialogAgain() = runBlocking {
        val bridge = LocalNetworkRequestBridge()
        var launched = 0
        bridge.attach { launched++ }
        async(start = CoroutineStart.UNDISPATCHED) { bridge.request() }.cancelAndJoin()
        val next = async(start = CoroutineStart.UNDISPATCHED) { bridge.request() }
        val answer = PermissionAnswer(granted = false)
        bridge.onResult(answer)
        assertEquals(answer, next.await())
        assertEquals(2, launched)
    }

    @Test
    fun aRecreatedActivityCompletesThePendingRequest() = runBlocking {
        val bridge = LocalNetworkRequestBridge()
        val old = {}
        bridge.attach(old)
        val pending = async(start = CoroutineStart.UNDISPATCHED) { bridge.request() }
        bridge.detach(old, finishing = false)
        bridge.attach {}
        val answer = PermissionAnswer(granted = false, canAskAgain = true)
        bridge.onResult(answer)
        assertEquals(answer, pending.await())
    }

    @Test
    fun aFinishingActivityEndsThePendingRequest() = runBlocking {
        val bridge = LocalNetworkRequestBridge()
        val launch = {}
        bridge.attach(launch)
        val pending = async(start = CoroutineStart.UNDISPATCHED) { bridge.request() }
        bridge.detach(launch, finishing = true)
        assertNull(pending.await())
        assertNull(bridge.request())
    }

    @Test
    fun aStaleDetachKeepsTheNewActivity() = runBlocking {
        val bridge = LocalNetworkRequestBridge()
        val old = {}
        var launched = 0
        bridge.attach(old)
        bridge.attach { launched++ }
        bridge.detach(old, finishing = false)
        val pending = async(start = CoroutineStart.UNDISPATCHED) { bridge.request() }
        bridge.onResult(PermissionAnswer(granted = true))
        pending.await()
        assertEquals(1, launched)
    }
}
