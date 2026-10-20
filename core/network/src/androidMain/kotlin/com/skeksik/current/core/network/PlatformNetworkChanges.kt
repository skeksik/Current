package com.skeksik.current.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import org.koin.core.scope.Scope

/**
 * The default network's callback (needs `ACCESS_NETWORK_STATE`). A report of the network that was already the default when listening
 * started is not a change (registering can report it at once, docs/platform-notes.md); another default network, or losing it, is.
 * Without the permission there is no signal.
 */
internal actual fun Scope.platformNetworkChanges(): NetworkChanges {
    val connectivity = get<Context>().getSystemService(ConnectivityManager::class.java) ?: return NetworkChanges.None
    return NetworkChanges {
        callbackFlow {
            val callback = object : ConnectivityManager.NetworkCallback() {
                private var last: Network? = connectivity.activeNetwork

                override fun onAvailable(network: Network) {
                    if (network != last) trySend(Unit)
                    last = network
                }

                override fun onLost(network: Network) {
                    if (network == last) {
                        last = null
                        trySend(Unit)
                    }
                }
            }
            try {
                connectivity.registerDefaultNetworkCallback(callback)
            } catch (e: SecurityException) {
                close()
                return@callbackFlow
            }
            awaitClose { connectivity.unregisterNetworkCallback(callback) }
        }
    }
}
