package com.skeksik.current.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import org.koin.core.scope.Scope

/**
 * The default network's callback (needs `ACCESS_NETWORK_STATE`). What counts as a change is [DefaultNetworkTracker]'s: registering
 * reports the current network at once (docs/platform-notes.md), which is not a change. Without the permission there is no signal.
 */
internal actual fun Scope.platformNetworkChanges(): NetworkChanges {
    val connectivity = get<Context>().getSystemService(ConnectivityManager::class.java) ?: return NetworkChanges.None
    return NetworkChanges {
        callbackFlow {
            val callback = try {
                DefaultNetworkCallback(DefaultNetworkTracker(connectivity.activeNetwork)) { trySend(Unit) }
                    .also { connectivity.registerDefaultNetworkCallback(it) }
            } catch (e: SecurityException) {
                close()
                return@callbackFlow
            }
            awaitClose { connectivity.unregisterNetworkCallback(callback) }
        }
    }
}

private class DefaultNetworkCallback(
    private val tracker: DefaultNetworkTracker<Network>,
    private val changed: () -> Unit,
) : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) {
        if (tracker.available(network)) changed()
    }

    override fun onLost(network: Network) {
        if (tracker.lost(network)) changed()
    }

    /** API 29+; earlier versions never call it. Android blocks a background app's network and unblocks it in the foreground. */
    override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
        if (tracker.blockedStatus(network, blocked)) changed()
    }
}
