package com.skeksik.current.core.network

/** The browser engine's failed fetch ([isKtorFetchFailure]); any other error stays a bug. */
internal actual fun isPlatformUnreachable(error: Throwable): Boolean = isKtorFetchFailure(error.message)
