package com.skeksik.current.core.database

/** "Chrome on Windows" from a browser's user agent, for server dashboards. Edge and Chrome on iOS say Safari too, so their checks come first. */
internal fun browserDeviceName(userAgent: String): String {
    val browser = when {
        "Edg/" in userAgent -> "Edge"
        "Firefox/" in userAgent -> "Firefox"
        "Chrome/" in userAgent || "CriOS/" in userAgent -> "Chrome"
        "Safari/" in userAgent -> "Safari"
        else -> "Browser"
    }
    val system = when {
        "Windows" in userAgent -> "Windows"
        "iPhone" in userAgent || "iPad" in userAgent -> "iOS"
        "Android" in userAgent -> "Android"
        "Mac OS X" in userAgent -> "macOS"
        "Linux" in userAgent -> "Linux"
        else -> null
    }
    return if (system == null) browser else "$browser on $system"
}
