package com.skeksik.current.core.network

/** The Darwin engine's connection failures are not known to come outside `IOException` (docs/platform-notes.md). */
internal actual fun isPlatformUnreachable(error: Throwable): Boolean = false
