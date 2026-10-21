package com.skeksik.current.core.network

import com.skeksik.current.core.model.SavedServer
import com.skeksik.current.core.model.ServerAddress

/*
 * Editing a saved server's addresses, which are in the user's priority order (ADR 0016, ADR 0019). An address is added only after the
 * same server answered there (`ServerSetupViewModel` with `addTo`); save the result with [ServerRepository.save].
 */

/** Adds [address] last, the lowest priority. */
fun SavedServer.withAddress(address: ServerAddress): SavedServer {
    require(addresses.none { it.url == address.url }) { "Address already saved" }
    return copy(addresses = addresses + address)
}

/** Whether [url] can be removed: a server keeps at least one address. */
fun SavedServer.canRemoveAddress(url: String): Boolean = addresses.size > 1 && addresses.any { it.url == url }

/** Removes [url]; see [canRemoveAddress]. */
fun SavedServer.withoutAddress(url: String): SavedServer {
    require(canRemoveAddress(url)) { "Not a removable address" }
    return copy(addresses = addresses.filterNot { it.url == url })
}

/** Moves the address at index [from] to index [to], shifting the ones between. */
fun SavedServer.withAddressMoved(from: Int, to: Int): SavedServer {
    require(from in addresses.indices && to in addresses.indices) { "No address at $from or $to" }
    if (from == to) return this
    val reordered = addresses.toMutableList().apply { add(to, removeAt(from)) }
    return copy(addresses = reordered)
}
