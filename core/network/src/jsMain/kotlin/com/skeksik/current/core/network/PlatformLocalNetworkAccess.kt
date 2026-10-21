package com.skeksik.current.core.network

import org.koin.core.scope.Scope

internal actual fun Scope.platformLocalNetworkAccess(): LocalNetworkAccess = LocalNetworkAccess.NotNeeded
