package com.skeksik.current.core.network

import org.koin.core.scope.Scope

internal actual fun platformInfo() =
    PlatformInfo { "Desktop ${System.getProperty("os.name")} ${System.getProperty("os.version")} (Java ${System.getProperty("java.version")})" }

internal actual fun platformLogEcho(scope: Scope): ((String) -> Unit)? =
    desktopLogEcho(System.getProperty(LOG_PROPERTY), System.getenv(LOG_VARIABLE))

/** `-Dcurrent.log=debug` or `CURRENT_LOG=debug` prints the request log to stderr. */
internal const val LOG_PROPERTY = "current.log"
internal const val LOG_VARIABLE = "CURRENT_LOG"

internal fun desktopLogEcho(property: String?, variable: String?): ((String) -> Unit)? =
    if (property.isDebug() || variable.isDebug()) { line -> System.err.println("[$LOG_PROPERTY] $line") } else null

private fun String?.isDebug() = this?.trim().equals("debug", ignoreCase = true)
