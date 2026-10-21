package com.skeksik.current

import androidx.compose.ui.awt.ComposeWindow
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference

/**
 * Windows draws a window's frame light unless the app asks for dark, whatever the system theme, and Compose Desktop has no option for it
 * (docs/platform-notes.md, "Windows title bar in dark mode"). This asks DWM directly; the frame stays native, so snap layouts stay.
 * Elsewhere, or when the call is not there (documented from Windows 11 build 22000), it does nothing.
 */
internal object WindowsTitleBar {
    @Suppress("FunctionName")
    private interface Dwmapi : Library {
        /** Returns an HRESULT: 0 on success. */
        fun DwmSetWindowAttribute(hwnd: Pointer, attribute: Int, value: IntByReference, size: Int): Int
    }

    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20

    private val dwmapi: Dwmapi? by lazy {
        if (!System.getProperty("os.name").startsWith("Windows")) return@lazy null
        try {
            Native.load("dwmapi", Dwmapi::class.java)
        } catch (e: UnsatisfiedLinkError) {
            null
        }
    }

    /** Draws [window]'s title bar dark or light. Call once the window is shown, and again when the theme changes. */
    fun setDark(window: ComposeWindow, dark: Boolean) {
        val api = dwmapi ?: return
        val handle = window.windowHandle
        if (handle == 0L) return
        api.DwmSetWindowAttribute(Pointer(handle), DWMWA_USE_IMMERSIVE_DARK_MODE, IntByReference(if (dark) 1 else 0), Int.SIZE_BYTES)
    }
}
