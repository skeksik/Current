package com.skeksik.current.core.network

import org.koin.core.scope.Scope

/** No small API for this here (docs/platform-notes.md); the periodic check covers it. */
internal actual fun Scope.platformNetworkChanges(): NetworkChanges = NetworkChanges.None
