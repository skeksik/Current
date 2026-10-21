package com.skeksik.current.core.network

import org.koin.core.scope.Scope

/** What a problem report says about the device: the platform and its OS version, never a device name or an address. */
fun interface PlatformInfo {
    fun describe(): String
}

internal expect fun platformInfo(): PlatformInfo

/**
 * Where the request log is also printed as it happens, or null for nowhere: logcat in an Android debug build, stderr on desktop and the
 * browser console only when their flag is set (docs/platform-notes.md). [scope] gives Android its `Context`.
 */
internal expect fun platformLogEcho(scope: Scope): ((String) -> Unit)?
