package com.skeksik.current.core.network

import java.net.InetAddress
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

    private fun nic(name: String, vararg addresses: String, up: Boolean = true, loopback: Boolean = false) = InterfaceReading(
        name,
        up,
        loopback,
        addresses.map { InetAddress.getByName(it.substringBefore('/')) to it.substringAfter('/').toShort() },
    )

    @Test
    fun linkLocalAddressesAndAdaptersWithOnlyThoseDoNotCount() {
        val home = listOf(nic("eth0", "192.168.1.20/24", "fe80::1/64"), nic("lo", "127.0.0.1/8", loopback = true))
        val withChurn = home + listOf(
            nic("veth1a2b3c", "fe80::42:acff:fe11:2/64"),
            nic("vEthernet (WSL)", "fe80::215:5dff:fe00:1/64", "169.254.10.20/16"),
            nic("eth1", "192.168.5.5/24", up = false),
        )
        assertEquals(setOf("eth0 [192.168.1.20/24]"), snapshotOf(home))
        assertEquals(snapshotOf(home), snapshotOf(withChurn))
        assertEquals(
            setOf("eth0 [192.168.1.20/24]", "vEthernet (Default Switch) [172.20.48.1/20]"),
            snapshotOf(home + nic("vEthernet (Default Switch)", "172.20.48.1/20")),
            "a virtual adapter with a routable address still counts",
        )
    }
}
