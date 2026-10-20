package com.skeksik.current.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class PolledInterfacesTest {
    @Test
    fun aChangeInTheInterfacesThatAreUpIsANetworkChange() = runTest {
        val readings = ArrayDeque(
            listOf<Set<String>?>(
                setOf("wlan0 [192.168.1.20/24]"),
                setOf("wlan0 [192.168.1.20/24]"),
                null,
                setOf("wlan0 [10.0.0.5/24]"),
                setOf("wlan0 [10.0.0.5/24]", "tun0 [100.64.0.2/32]"),
            ),
        )
        var changes = 0
        val last = setOf("wlan0 [10.0.0.5/24]", "tun0 [100.64.0.2/32]")
        PolledInterfaces(5.seconds, { if (readings.isEmpty()) last else readings.removeFirst() }, StandardTestDispatcher(testScheduler))
            .changes()
            .onEach { changes++ }
            .launchIn(backgroundScope)
        runCurrent()
        // First reading at start, then one every 5 s: same, unreadable, another subnet, a VPN up.
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(0, changes, "the network at start is not a change")
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(0, changes, "an unreadable list is not a change")
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(1, changes)
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(2, changes)
        advanceTimeBy(20.seconds)
        runCurrent()
        assertEquals(2, changes)
    }
}
