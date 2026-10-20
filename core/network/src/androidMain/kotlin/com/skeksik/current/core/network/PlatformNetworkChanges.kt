package com.skeksik.current.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import org.koin.core.scope.Scope

/**
 * The default network's callback (needs `ACCESS_NETWORK_STATE`). Registering reports the current network at once; that first report
 * is not a change and is skipped. A different default network, or losing it, is.
 */
internal actual fun Scope.platformNetworkChanges(): NetworkChanges {
    val connectivity = get<Context>().getSystemService(ConnectivityManager::class.java) ?: return NetworkChanges.None
    return NetworkChanges {
        callbackFlow {
            val callback = object : ConnectivityManager.NetworkCallback() {
                private var last: Network? = null
                private var seen = false

                override fun onAvailable(network: Network) {
                    if (seen && network != last) trySend(Unit)
                    seen = true
                    last = network
                }

                override fun onLost(network: Network) {
                    if (network == last) {
                        last = null
                        trySend(Unit)
                    }
                }
            }
            connectivity.registerDefaultNetworkCallback(callback)
            awaitClose { connectivity.unregisterNetworkCallback(callback) }
        }
    }
}
