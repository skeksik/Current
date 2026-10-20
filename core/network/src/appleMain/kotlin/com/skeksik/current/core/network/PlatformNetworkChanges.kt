package com.skeksik.current.core.network

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import org.koin.core.scope.Scope
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.darwin.dispatch_queue_create

/**
 * Network framework's path monitor (`NWPathMonitor` in Swift). Every path update after the first is a change; the first is taken as
 * the path at start. Apple's reference does not say that a monitor reports the current path when it starts, nor whether a move between
 * two Wi-Fi networks produces an update (docs/platform-notes.md): both are to be checked on a device.
 */
internal actual fun Scope.platformNetworkChanges(): NetworkChanges = NetworkChanges {
    callbackFlow {
        val monitor = nw_path_monitor_create()
        var first = true
        nw_path_monitor_set_update_handler(monitor) { _ ->
            if (first) first = false else trySend(Unit)
        }
        nw_path_monitor_set_queue(monitor, dispatch_queue_create("com.skeksik.current.network-changes", null))
        nw_path_monitor_start(monitor)
        awaitClose { nw_path_monitor_cancel(monitor) }
    }
}
