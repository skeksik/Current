package com.skeksik.current.core.network

import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.koin.core.scope.Scope

/**
 * The JDK has no network change event (docs/platform-notes.md), so the interfaces are polled: a change in which ones are up, or in
 * their addresses, is a network change. A move to another network that keeps the same addresses (same subnet, same lease) is not seen;
 * failed requests and the periodic check cover that.
 */
internal actual fun Scope.platformNetworkChanges(): NetworkChanges = PolledInterfaces()

internal class PolledInterfaces(
    private val every: Duration = 5.seconds,
    private val read: () -> Set<String>? = ::interfacesUp,
    /** Where reading and waiting run; tests pass their own clock. */
    private val context: CoroutineContext = Dispatchers.IO,
) : NetworkChanges {
    override fun changes() = flow {
        var last = read()
        while (true) {
            delay(every)
            // An unreadable list (an I/O error) is not a change.
            val now = read() ?: continue
            if (now != last) {
                if (last != null) emit(Unit)
                last = now
            }
        }
    }.flowOn(context)
}

/** One interface as read: its name, whether it is up or loopback, and its addresses with their prefix lengths. */
internal class InterfaceReading(val name: String, val up: Boolean, val loopback: Boolean, val addresses: List<Pair<InetAddress, Short>>)

private fun interfacesUp(): Set<String>? = try {
    snapshotOf(
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map { nic ->
            InterfaceReading(nic.name, nic.isUp, nic.isLoopback, nic.interfaceAddresses.map { it.address to it.networkPrefixLength })
        },
    )
} catch (e: SocketException) {
    null
}

/**
 * Each interface that is up and not loopback, with its addresses other than link-local, as `name [address/prefix, ...]`. An interface
 * left with no address is dropped: virtual adapters that only have link-local addresses (Docker's `veth*`, idle Hyper-V and WSL
 * `vEthernet` adapters) come and go without the network changing.
 */
internal fun snapshotOf(interfaces: List<InterfaceReading>): Set<String> = interfaces
    .filter { it.up && !it.loopback }
    .mapNotNull { nic ->
        val addresses = nic.addresses.filterNot { (address, _) -> address.isLinkLocalAddress }
            .map { (address, prefix) -> "${address.hostAddress}/$prefix" }
            .sorted()
        addresses.takeIf { it.isNotEmpty() }?.let { "${nic.name} $it" }
    }
    .toSet()
