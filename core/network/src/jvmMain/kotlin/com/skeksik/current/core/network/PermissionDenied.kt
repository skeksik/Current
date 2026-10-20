package com.skeksik.current.core.network

/** The JDK's HTTP client never refuses network access this way. */
internal actual fun isPermissionDenied(error: Throwable): Boolean = false
