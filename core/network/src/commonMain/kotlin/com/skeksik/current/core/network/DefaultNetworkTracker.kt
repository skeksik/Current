package com.skeksik.current.core.network

/**
 * Decides which reports about the default network are a network change (Android's default network callback; ADR 0019). [N] is the
 * platform's network handle. Seeded with the network that was the default when listening started, whose own reports are not changes.
 *
 * A change: another network becomes the default, the default is lost, or the default network becomes usable again after the system
 * blocked the app's access to it (in the background), since a check made while it was blocked found nothing.
 */
internal class DefaultNetworkTracker<N : Any>(private var current: N?) {
    private var blocked = false

    fun available(network: N): Boolean {
        val changed = network != current
        if (changed) blocked = false
        current = network
        return changed
    }

    fun lost(network: N): Boolean {
        if (network != current) return false
        current = null
        blocked = false
        return true
    }

    fun blockedStatus(network: N, isBlocked: Boolean): Boolean {
        if (network != current) return false
        val unblocked = blocked && !isBlocked
        blocked = isBlocked
        return unblocked
    }
}
