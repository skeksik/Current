package com.skeksik.current.core.network

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.koin.core.scope.Scope

/**
 * Says that the device's network may have changed, so the server addresses are probed again (ADR 0019). No platform says which network
 * the device is on (docs/platform-notes.md, "Detecting network changes"), so an event carries nothing.
 */
fun interface NetworkChanges {
    /** One element per change. Collecting starts listening; the first element is a change after that, not the current network. */
    fun changes(): Flow<Unit>

    companion object {
        /** No signal: the periodic check and failed requests are all there is (desktop, Apple until it has one). */
        val None = NetworkChanges { emptyFlow() }
    }
}

/** This platform's [NetworkChanges]. */
internal expect fun Scope.platformNetworkChanges(): NetworkChanges
