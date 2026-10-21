package com.skeksik.current.core.network

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import org.koin.core.scope.Scope

/**
 * The window's `online` event, and `navigator.connection`'s `change` where it exists (Chromium only). Neither says which network the
 * browser is on (docs/platform-notes.md), which is all [NetworkChanges] asks.
 */
internal actual fun Scope.platformNetworkChanges(): NetworkChanges = NetworkChanges {
    callbackFlow {
        val listener: (dynamic) -> Unit = { trySend(NetworkChange.Changed) }
        listen(listener)
        awaitClose { unlisten(listener) }
    }
}

private fun listen(listener: (dynamic) -> Unit): Unit = js(
    """(function () {
        if (typeof window === 'undefined') return;
        window.addEventListener('online', listener);
        if (typeof navigator !== 'undefined' && navigator.connection) navigator.connection.addEventListener('change', listener);
    })()""",
)

private fun unlisten(listener: (dynamic) -> Unit): Unit = js(
    """(function () {
        if (typeof window === 'undefined') return;
        window.removeEventListener('online', listener);
        if (typeof navigator !== 'undefined' && navigator.connection) navigator.connection.removeEventListener('change', listener);
    })()""",
)
