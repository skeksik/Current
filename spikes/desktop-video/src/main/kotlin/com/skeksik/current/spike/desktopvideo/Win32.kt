package com.skeksik.current.spike.desktopvideo

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.Component
import java.awt.Rectangle

@Suppress("FunctionName")
private interface User32 : Library {
    fun GetWindowRect(hwnd: Pointer, rect: Pointer): Boolean
    fun GetWindowLongPtrW(hwnd: Pointer, index: Int): Long
    fun SetWindowLongPtrW(hwnd: Pointer, index: Int, value: Long): Long
    fun SetWindowPos(hwnd: Pointer, insertAfter: Pointer?, x: Int, y: Int, cx: Int, cy: Int, flags: Int): Boolean
    fun MonitorFromWindow(hwnd: Pointer, flags: Int): Pointer?
    fun GetMonitorInfoW(monitor: Pointer, info: Pointer): Boolean
}

private const val GWL_STYLE = -16

// WS_CAPTION, WS_THICKFRAME, WS_SYSMENU, WS_MINIMIZEBOX, WS_MAXIMIZEBOX
private const val WS_FRAME_BITS = 0x00C00000L or 0x00040000L or 0x00080000L or 0x00020000L or 0x00010000L
private const val SWP_NOZORDER = 0x0004
private const val SWP_FRAMECHANGED = 0x0020
private const val SWP_NOOWNERZORDER = 0x0200
private const val MONITOR_DEFAULTTONEAREST = 2

/**
 * Borderless fullscreen on the same HWND: strips the caption and frame styles and covers the monitor, then restores both.
 * No AWT full-screen exclusive mode, so Windows never resizes the frame behind AWT's back.
 */
object BorderlessFullscreen {
    private var savedStyle = 0L
    private var savedRect: Rectangle? = null

    fun enter(window: java.awt.Window): Boolean {
        val lib = user32 ?: return false
        val hwnd = Native.getComponentPointer(window) ?: return false
        val rect = nativeRect(window) ?: return false
        val monitor = lib.MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST) ?: return false
        // MONITORINFO: cbSize, rcMonitor (left, top, right, bottom), rcWork, dwFlags
        val info = Memory(40).apply { setInt(0, 40) }
        if (!lib.GetMonitorInfoW(monitor, info)) return false
        val left = info.getInt(4)
        val top = info.getInt(8)
        val width = info.getInt(12) - left
        val height = info.getInt(16) - top
        savedStyle = lib.GetWindowLongPtrW(hwnd, GWL_STYLE)
        savedRect = rect
        lib.SetWindowLongPtrW(hwnd, GWL_STYLE, savedStyle and WS_FRAME_BITS.inv())
        lib.SetWindowPos(hwnd, null, left, top, width, height, SWP_NOZORDER or SWP_NOOWNERZORDER or SWP_FRAMECHANGED)
        Diagnostics.event("borderless fullscreen: on, monitor ${width}x$height@$left,$top")
        return true
    }

    fun exit(window: java.awt.Window) {
        val lib = user32 ?: return
        val rect = savedRect ?: return
        val hwnd = Native.getComponentPointer(window) ?: return
        lib.SetWindowLongPtrW(hwnd, GWL_STYLE, savedStyle)
        lib.SetWindowPos(hwnd, null, rect.x, rect.y, rect.width, rect.height, SWP_NOZORDER or SWP_NOOWNERZORDER or SWP_FRAMECHANGED)
        savedRect = null
        Diagnostics.event("borderless fullscreen: off, back to ${rect.width}x${rect.height}@${rect.x},${rect.y}")
    }
}

private val user32: User32? by lazy {
    if (System.getProperty("os.name").startsWith("Windows")) Native.load("user32", User32::class.java) else null
}

/** The native window's rectangle in physical screen pixels (Win32 `GetWindowRect`), or null off Windows or without a peer. */
fun nativeRect(component: Component): Rectangle? {
    val lib = user32 ?: return null
    if (!component.isDisplayable) return null
    val hwnd = Native.getComponentPointer(component) ?: return null
    val rect = Memory(16)
    if (!lib.GetWindowRect(hwnd, rect)) return null
    val left = rect.getInt(0)
    val top = rect.getInt(4)
    return Rectangle(left, top, rect.getInt(8) - left, rect.getInt(12) - top)
}

/** References to the player's windows so the self-test can dump how AWT and Win32 see their geometry. */
object GeometryProbe {
    @Volatile var frame: java.awt.Frame? = null
    @Volatile var canvas: Component? = null
    @Volatile var dialog: java.awt.Window? = null
    @Volatile var player: SpikePlayer? = null

    fun dump(tag: String) {
        fun Rectangle?.s() = this?.let { "${it.width}x${it.height}@${it.x},${it.y}" } ?: "-"
        val f = frame
        val c = canvas
        val d = dialog
        val canvasScreen = c?.takeIf { it.isShowing }?.let { Rectangle(it.locationOnScreen, it.size) }
        Diagnostics.event(
            "geometry $tag: frame awt=${f?.bounds.s()} native=${f?.let(::nativeRect).s()} " +
                "scale=${f?.graphicsConfiguration?.defaultTransform?.scaleX} state=${(f as? java.awt.Frame)?.extendedState} " +
                "insets=${f?.insets?.let { "${it.top},${it.left},${it.bottom},${it.right}" }}; " +
                "content area=${(f as? javax.swing.JFrame)?.contentPane?.takeIf { it.isShowing }?.let { Rectangle(it.locationOnScreen, it.size) }.s()}; " +
                "canvas awt=${canvasScreen.s()} native=${c?.let(::nativeRect).s()}; " +
                "dialog awt=${d?.bounds.s()} native=${d?.let(::nativeRect).s()} visible=${d?.isVisible}; " +
                "mpv osd=${player?.getString("osd-width")}x${player?.getString("osd-height")}",
        )
    }
}
