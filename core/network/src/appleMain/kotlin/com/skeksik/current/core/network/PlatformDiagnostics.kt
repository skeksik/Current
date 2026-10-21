package com.skeksik.current.core.network

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform
import org.koin.core.scope.Scope
import platform.Foundation.NSProcessInfo

@OptIn(ExperimentalNativeApi::class)
internal actual fun platformInfo() =
    PlatformInfo { "${Platform.osFamily.name} ${NSProcessInfo.processInfo.operatingSystemVersionString}" }

// Not printed yet: the unified log comes with the Apple app (docs/reviews/2026-10/network.md, N16).
internal actual fun platformLogEcho(scope: Scope): ((String) -> Unit)? = null
