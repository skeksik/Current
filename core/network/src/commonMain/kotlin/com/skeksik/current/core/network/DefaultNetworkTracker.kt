package com.skeksik.current.core.network

/**
 * Decides what reports about the default network mean (Android's default network callback; ADR 0019). [N] is the platform's network
 * handle. Seeded with the network that was the default when listening started, whose own reports are not changes.
 *
 * [NetworkChange.Changed]: another network becomes the default, or the default is lost. [NetworkChange.Suspended]: the system blocks
 * the app's access to the network, which it does about 5 s after the app goes to the background and keeps while the app is frozen.
 * [NetworkChange.Resumed]: the app's access is unblocked. Blocking is the app's, not a network's: a switch while blocked is a change
 * of its own, and the app stays suspended until the new default is reported unblocked.
 */
internal class DefaultNetworkTracker<N : Any>(private var current: N?) {
    private var blocked = false

    fun available(network: N): List<NetworkChange> {
        if (network == current) return emptyList()
        current = network
        return listOf(NetworkChange.Changed)
    }

    fun lost(network: N): List<NetworkChange> {
        if (network != current) return emptyList()
        current = null
        return listOf(NetworkChange.Changed)
    }

    /** On the default network's callback, a report for another network means the default changed, whatever came before. */
    fun blockedStatus(network: N, isBlocked: Boolean): List<NetworkChange> = buildList {
        addAll(available(network))
        if (isBlocked && !blocked) add(NetworkChange.Suspended)
        if (!isBlocked && blocked) add(NetworkChange.Resumed)
        blocked = isBlocked
    }
}
