@file:OptIn(ExperimentalForeignApi::class)

package com.skeksik.current.core.network

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import org.koin.core.scope.Scope
import platform.Foundation.NSNotificationCenter
import platform.Network.nw_endpoint_copy_address_string
import platform.Network.nw_interface_get_name
import platform.Network.nw_interface_get_type
import platform.Network.nw_path_enumerate_gateways
import platform.Network.nw_path_enumerate_interfaces
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_t
import platform.darwin.dispatch_queue_create
import platform.posix.free

/**
 * Network framework's path monitor (`NWPathMonitor` in Swift). A path update is a change only when the path differs from the last one
 * in its status, interfaces (names and types) or gateways: the monitor reports the current path at start, and on cellular repeats
 * unchanged paths every few seconds (docs/platform-notes.md). Going to the background on iOS and tvOS is [NetworkChange.Suspended] and
 * coming back to the foreground is [NetworkChange.Resumed], both observed by the notification's name, which macOS never posts.
 */
internal actual fun Scope.platformNetworkChanges(): NetworkChanges = NetworkChanges {
    callbackFlow {
        val monitor = nw_path_monitor_create()
        var last: String? = null
        nw_path_monitor_set_update_handler(monitor) { path ->
            val now = signature(path)
            if (last != null && now != last) trySend(NetworkChange.Changed)
            last = now
        }
        nw_path_monitor_set_queue(monitor, dispatch_queue_create("com.skeksik.current.network-changes", null))
        nw_path_monitor_start(monitor)
        val center = NSNotificationCenter.defaultCenter
        val background = center.addObserverForName(BACKGROUND, null, null) { _ -> trySend(NetworkChange.Suspended) }
        val foreground = center.addObserverForName(FOREGROUND, null, null) { _ -> trySend(NetworkChange.Resumed) }
        awaitClose {
            nw_path_monitor_cancel(monitor)
            center.removeObserver(background)
            center.removeObserver(foreground)
        }
    }
}

/** UIKit's background and foreground notifications, by name, so this source set needs no UIKit. */
private const val BACKGROUND = "UIApplicationDidEnterBackgroundNotification"
private const val FOREGROUND = "UIApplicationWillEnterForegroundNotification"

/** What tells two paths apart: status, then each interface's name and type, then each gateway's address. */
private fun signature(path: nw_path_t): String = buildString {
    if (path == null) return@buildString
    append(nw_path_get_status(path))
    nw_path_enumerate_interfaces(path) { network ->
        append(' ').append(nw_interface_get_name(network)?.toKString()).append(':').append(nw_interface_get_type(network))
        true
    }
    append(" |")
    nw_path_enumerate_gateways(path) { gateway ->
        val address = nw_endpoint_copy_address_string(gateway)
        if (address != null) {
            append(' ').append(address.toKString())
            free(address)
        }
        true
    }
}
