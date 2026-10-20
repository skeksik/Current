package com.skeksik.current.core.network

internal actual fun isPermissionDenied(error: Throwable): Boolean = error is SecurityException
