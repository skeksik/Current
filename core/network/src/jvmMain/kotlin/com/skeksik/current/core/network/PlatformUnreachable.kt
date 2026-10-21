package com.skeksik.current.core.network

/** The JDK's HTTP client reports every connection failure as an `IOException`. */
internal actual fun isPlatformUnreachable(error: Throwable): Boolean = false
