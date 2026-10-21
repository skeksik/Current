package com.skeksik.current.core.database

import platform.UIKit.UIDevice

/**
 * A generic name such as "iPhone" since iOS 16 (the user-assigned name needs an entitlement). Never `NSProcessInfo.hostName`: that
 * lookup raises the Local Network prompt and blocks until it is answered, which got the app killed at launch (docs/platform-notes.md).
 */
internal actual fun platformDeviceName(): String = UIDevice.currentDevice.name
