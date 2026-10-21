package com.skeksik.current.core.network

import org.koin.core.scope.Scope

internal actual fun platformInfo() = PlatformInfo { "Web, ${userAgent()}" }

/** `localStorage.setItem('current.log', 'debug')`, then a reload, prints the request log with `console.debug` (Verbose level). */
internal actual fun platformLogEcho(scope: Scope): ((String) -> Unit)? =
    if (storedLogLevel().equals("debug", ignoreCase = true)) { line -> console.asDynamic().debug(line) } else null

// Storage access throws when site data is blocked; then nothing is printed.
private fun storedLogLevel(): String? = try {
    js("globalThis.localStorage ? globalThis.localStorage.getItem('current.log') : null") as String?
} catch (e: Throwable) {
    null
}

private fun userAgent(): String = js("globalThis.navigator ? globalThis.navigator.userAgent : 'unknown'") as String
