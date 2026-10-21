@file:OptIn(ExperimentalForeignApi::class)

package com.skeksik.current.core.database

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.CFBridgingRelease
import platform.SystemConfiguration.SCDynamicStoreCopyComputerName

/**
 * The computer name from Sharing settings, read from the local configuration store. Never `NSProcessInfo.hostName`: that may
 * resolve over the network and wait on the Local Network prompt (docs/platform-notes.md).
 */
internal actual fun platformDeviceName(): String =
    (CFBridgingRelease(SCDynamicStoreCopyComputerName(null, null)) as? String)?.takeIf { it.isNotBlank() } ?: "Mac"
