package com.skeksik.current.core.network

/** The browser engine's failed fetch, a `kotlin.Error` ([isKtorFetchFailure]); any other error stays a bug. */
internal actual fun isPlatformUnreachable(error: Throwable): Boolean = error is Error && isKtorFetchFailure(error.message)
