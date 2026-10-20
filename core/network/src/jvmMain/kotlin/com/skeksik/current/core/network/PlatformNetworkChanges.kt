package com.skeksik.current.core.network

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

/** Each interface that is up and not loopback, with its addresses, as `name address/prefix ...`; null when they cannot be read. */
private fun interfacesUp(): Set<String>? = try {
    NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .filter { it.isUp && !it.isLoopback }
        .map { nic -> nic.name + " " + nic.interfaceAddresses.map { "${it.address.hostAddress}/${it.networkPrefixLength}" }.sorted() }
        .toSet()
} catch (e: SocketException) {
    null
}
