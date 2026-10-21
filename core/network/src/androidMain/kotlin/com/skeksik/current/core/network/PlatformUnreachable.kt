package com.skeksik.current.core.network

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

private val logged = AtomicBoolean(false)

/**
 * Android's sockets throw a `SecurityException` naming the `INTERNET` permission when the app lacks it ([isMissingInternetPermission]).
 * Only that one counts. It is logged once, so an app shell missing the permission does not pass for a server that is down.
 */
internal actual fun isPlatformUnreachable(error: Throwable): Boolean {
    val denied = error is SecurityException && isMissingInternetPermission(error.message)
    if (denied && logged.compareAndSet(false, true)) Log.w("Current", "Network access denied to the app: missing INTERNET permission")
    return denied
}
