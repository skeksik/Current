package com.skeksik.current

import com.sun.jna.Library
import com.sun.jna.Native

/**
 * Windows lets only some processes bring a window to the front; a background app's `toFront` may just flash its taskbar button
 * (docs/platform-notes.md, "Single instance on desktop"). A launch the user just started may hand that right to the running app before
 * signalling it. Elsewhere, or when the call fails, it does nothing: the signal goes out either way.
 */
internal object WindowsForeground {
    @Suppress("FunctionName")
    private interface User32 : Library {
        /** Returns a Windows BOOL: nonzero on success. */
        fun AllowSetForegroundWindow(processId: Int): Int
    }

    private val user32: User32? by lazy {
        if (!System.getProperty("os.name").startsWith("Windows")) return@lazy null
        try {
            Native.load("user32", User32::class.java)
        } catch (e: UnsatisfiedLinkError) {
            null
        }
    }

    /** Lets process [pid] set the foreground window. */
    fun allow(pid: Long) {
        user32?.AllowSetForegroundWindow(pid.toInt())
    }
}
