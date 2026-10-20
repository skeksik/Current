package com.skeksik.current.core.network

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

private val logged = AtomicBoolean(false)

/**
 * Android's sockets throw `SecurityException("Permission denied (missing INTERNET permission?)")` when the app lacks `INTERNET`. Only
 * that one counts. It is logged once, so an app shell missing the permission does not pass for a server that is down.
 */
internal actual fun isPermissionDenied(error: Throwable): Boolean {
    val denied = error is SecurityException && error.message.orEmpty().contains("INTERNET")
    if (denied && logged.compareAndSet(false, true)) Log.w("Current", "Network access denied to the app: ${error.message}")
    return denied
}
