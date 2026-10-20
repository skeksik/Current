package com.skeksik.current.core.network

/**
 * Decides what reports about the default network mean (Android's default network callback; ADR 0019). [N] is the platform's network
 * handle. Seeded with the network that was the default when listening started, whose own reports are not changes.
 *
 * [NetworkChange.Changed]: another network becomes the default, or the default is lost. [NetworkChange.Resumed]: the default network
 * becomes usable again after the system blocked the app's access to it (in the background); a check made while blocked found nothing,
 * but a switch made meanwhile was already reported as a change of its own.
 */
internal class DefaultNetworkTracker<N : Any>(private var current: N?) {
    private var blocked = false

    fun available(network: N): NetworkChange? {
        if (network == current) return null
        current = network
        blocked = false
        return NetworkChange.Changed
    }

    fun lost(network: N): NetworkChange? {
        if (network != current) return null
        current = null
        blocked = false
        return NetworkChange.Changed
    }

    /** On the default network's callback, a report for another network means the default changed, whatever came before. */
    fun blockedStatus(network: N, isBlocked: Boolean): NetworkChange? {
        val change = if (network != current) available(network) else null
        val unblocked = blocked && !isBlocked
        blocked = isBlocked
        return change ?: if (unblocked) NetworkChange.Resumed else null
    }
}
