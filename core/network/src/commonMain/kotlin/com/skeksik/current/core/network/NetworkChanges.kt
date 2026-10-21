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
    fun changes(): Flow<NetworkChange>

    companion object {
        /** No signal: the periodic check and failed requests are all there is (Android without its permission, tests). */
        val None = NetworkChanges { emptyFlow() }
    }
}

/** What a [NetworkChanges] element reports. */
enum class NetworkChange {
    /** The network may be another one: what answered before proves nothing now. */
    Changed,

    /**
     * The network became usable again: the system stopped blocking the app's access to it (Android, in the background), or the app
     * came back to the foreground (iOS and tvOS, after [Suspended]). The addresses are asked again at once.
     */
    Resumed,

    /**
     * The app is going to the background, where the system may suspend it (iOS and tvOS). Nothing runs while suspended, and the network
     * may change meanwhile, so the address in use is proved again before requests use it after [Resumed].
     */
    Suspended,
}

/** This platform's [NetworkChanges]. */
internal expect fun Scope.platformNetworkChanges(): NetworkChanges
