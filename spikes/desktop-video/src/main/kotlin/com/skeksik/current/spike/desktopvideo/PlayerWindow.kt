package com.skeksik.current.spike.desktopvideo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import com.sun.jna.Native
import kotlinx.coroutines.delay
import java.awt.Canvas
import java.awt.Component
import java.awt.Cursor
import java.awt.Dialog
import java.awt.Graphics
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities

/** UI state shared by the video window and the controls window. */
class OverlayState {
    var controlsVisible by mutableStateOf(true)
    var lastActivity by mutableStateOf(System.nanoTime())
    var showStats by mutableStateOf(true)

    /** Hide the whole controls window (not just its content) while idle, so nothing sits above the video. */
    var hideWindowWhenIdle by mutableStateOf(false)

    /** Alignment samples (every 250 ms) in which the controls window did not cover exactly the video canvas. */
    @Volatile var misalignedSamples = 0
    @Volatile var alignmentSamples = 0

    /** Size Compose laid the controls out for, in pixels, to check it against the window it is in. */
    @Volatile var contentSizePx: androidx.compose.ui.unit.IntSize? = null

    /** How often the controls window was shown again after hiding (H mode). */
    @Volatile var windowShows = 0
    private var lastPointer: Point? = null

    /**
     * Activity only when the pointer really moved: hiding the controls window makes Windows send a mouse move to the window
     * below even though the cursor stands still, which would bring the controls straight back.
     */
    fun pointerMoved() {
        val now = java.awt.MouseInfo.getPointerInfo()?.location ?: return
        val last = lastPointer
        lastPointer = now
        if (last == null || kotlin.math.abs(now.x - last.x) > 1 || kotlin.math.abs(now.y - last.y) > 1) activity()
    }

    fun activity() {
        lastActivity = System.nanoTime()
    }
}

/**
 * Owns the mpv instance of one player window. mpv starts once the canvas has a native window, outside AWT's tree lock;
 * it restarts if that native window is ever recreated (mpv would otherwise draw into a destroyed HWND), and it stops
 * before the window is disposed.
 */
class PlayerSession(private val request: PlayRequest) {
    var player by mutableStateOf<MpvPlayer?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var wid = 0L
    private var closed = false

    /** On the EDT, after the canvas got its native window [newWid]. */
    fun attach(newWid: Long) {
        if (closed || (player != null && newWid == wid)) return
        if (player != null) {
            Diagnostics.event("canvas native window changed ($wid -> $newWid): restarting mpv")
            stopPlayer()
        }
        wid = newWid
        try {
            player = MpvPlayer(request, if (request.embed) newWid else 0L, request.parsedExtraOptions())
        } catch (e: Exception) {
            Diagnostics.crash("starting mpv", e)
            error = "Could not start mpv: ${e.message}"
        }
    }

    /** On the EDT, just before the canvas's native window is destroyed. */
    fun canvasDestroying() {
        if (closed || player == null) return
        Diagnostics.event("canvas native window $wid destroyed while mpv uses it: stopping mpv")
        if (request.embed) stopPlayer()
    }

    fun close() {
        if (closed) return
        closed = true
        stopPlayer()
    }

    private fun stopPlayer() {
        val current = player ?: return
        player = null
        current.close()
    }
}

/**
 * Candidate 2: a Compose window whose content is a heavyweight AWT [VideoCanvas]; libmpv draws into it through `wid`.
 * The controls live in a separate undecorated, transparent [ComposeDialog] owned by that window and kept on top of
 * the canvas, because Compose cannot draw over a heavyweight component in the same window.
 */
@Composable
fun PlayerWindow(request: PlayRequest, onClosed: () -> Unit) {
    val windowState = rememberWindowState(width = 1280.dp, height = 760.dp)
    val overlay = remember { OverlayState().apply { showStats = request.stats } }
    val session = remember { PlayerSession(request) }
    val close = {
        Diagnostics.event("player window: close requested")
        session.close()
        onClosed()
    }

    Window(
        onCloseRequest = close,
        state = windowState,
        title = "Spike: ${displayName(request.source)}",
        onPreviewKeyEvent = { event -> session.player?.let { handleKey(event, it, windowState, overlay) } ?: false },
    ) {
        DisposableEffect(window) {
            val listener = WindowEventLogger(window)
            GeometryProbe.frame = window
            onDispose { listener.remove() }
        }
        val canvas = remember {
            VideoCanvas(
                onPeerCreated = session::attach,
                onPeerDestroying = session::canvasDestroying,
                overlay = overlay,
                onDoubleClick = { toggleFullscreen(windowState) },
            )
        }
        GeometryProbe.canvas = canvas
        GeometryProbe.player = session.player
        Box(Modifier.fillMaxSize()) {
            SwingPanel(background = Color.Black, factory = { canvas }, modifier = Modifier.fillMaxSize())
            session.error?.let { Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center).background(Color(0xCCB00020)).padding(16.dp)) }
        }

        val currentPlayer = session.player
        if (currentPlayer != null) {
            val state by currentPlayer.state.collectAsState()
            val recorder = if (request.stats) {
                rememberStatsRecorder(
                    currentPlayer,
                    isFullscreen = { Fullscreen.isOn(windowState) },
                    isControlsWindowVisible = { request.controlsWindow && (overlay.controlsVisible || !overlay.hideWindowWhenIdle) },
                )
            } else {
                null
            }
            LaunchedEffect(overlay.lastActivity, state.paused) {
                overlay.controlsVisible = true
                if (!state.paused) {
                    delay(3_000)
                    overlay.controlsVisible = false
                }
            }
            LaunchedEffect(overlay.controlsVisible) {
                canvas.cursor = if (overlay.controlsVisible) Cursor.getDefaultCursor() else blankCursor
            }
            if (request.controlsWindow) {
                ControlsWindow(
                    owner = window,
                    anchor = canvas,
                    overlay = overlay,
                    onPreviewKeyEvent = { event -> handleKey(event, currentPlayer, windowState, overlay) },
                ) {
                    PlayerControls(
                        player = currentPlayer,
                        recorder = recorder,
                        title = displayName(request.source),
                        overlay = overlay,
                        isFullscreen = Fullscreen.isOn(windowState),
                        onToggleFullscreen = { toggleFullscreen(windowState) },
                    )
                }
            }
        }
        if (request.selfTest) {
            SelfTest(session, windowState, overlay, onFinished = close)
        }
    }
}

/**
 * Fullscreen, in the mode chosen by `spike.fullscreen`:
 * - `compose`: Compose's `WindowPlacement.Fullscreen` (AWT's full-screen exclusive mode on Windows);
 * - `compose-fix`: the same, then the frame's bounds are applied again after leaving, so AWT resizes the native window;
 * - `borderless`: [BorderlessFullscreen] on the same HWND, without AWT's full-screen mode.
 */
object Fullscreen {
    var mode: String = "borderless"
    var borderlessOn by mutableStateOf(false)
        private set

    fun isOn(windowState: WindowState) =
        if (mode == "borderless") borderlessOn else windowState.placement == WindowPlacement.Fullscreen

    /** Set while borderless fullscreen was entered from a maximized window, to maximize again on the way out. */
    private var restoreMaximized = false
    private var switching = false

    fun set(windowState: WindowState, on: Boolean) {
        if (isOn(windowState) == on || switching) return
        if (mode == "borderless") {
            val frame = GeometryProbe.frame ?: return
            if (on) {
                // From a maximized window, restore it through Compose/AWT first: restyling a maximized HWND behind AWT's back left
                // AWT with the fullscreen bounds in about half the runs, so content and controls were laid out for the wrong rectangle.
                restoreMaximized = windowState.placement == WindowPlacement.Maximized
                if (restoreMaximized) {
                    switching = true
                    windowState.placement = WindowPlacement.Floating
                    afterNormal(frame) {
                        switching = false
                        borderlessOn = BorderlessFullscreen.enter(frame)
                    }
                } else {
                    borderlessOn = BorderlessFullscreen.enter(frame)
                }
            } else {
                BorderlessFullscreen.exit(frame)
                borderlessOn = false
                if (restoreMaximized) {
                    restoreMaximized = false
                    // Maximize through AWT once it has seen the restored frame; set at once, it was lost.
                    switching = true
                    javax.swing.Timer(100) {
                        windowState.placement = WindowPlacement.Maximized
                        frame.extendedState = frame.extendedState or java.awt.Frame.MAXIMIZED_BOTH
                        switching = false
                        checkAwtInSync(frame)
                    }.apply { isRepeats = false }.start()
                } else {
                    checkAwtInSync(frame)
                }
            }
            return
        }
        windowState.placement = if (on) WindowPlacement.Fullscreen else WindowPlacement.Floating
        if (!on && mode == "compose-fix") {
            javax.swing.Timer(300) {
                val frame = GeometryProbe.frame ?: return@Timer
                val b = frame.bounds
                frame.setBounds(b.x, b.y, b.width + 1, b.height)
                frame.bounds = b
                Diagnostics.event("compose-fix: frame bounds applied again after fullscreen")
            }.apply { isRepeats = false }.start()
        }
    }
}

fun toggleFullscreen(windowState: WindowState) = Fullscreen.set(windowState, !Fullscreen.isOn(windowState))

/** Runs [action] on the EDT once [frame] is no longer maximized (polled every 20 ms, at most 1 s). */
private fun afterNormal(frame: java.awt.Frame, action: () -> Unit) {
    val started = System.currentTimeMillis()
    javax.swing.Timer(20, null).apply {
        addActionListener {
            val normal = frame.extendedState and java.awt.Frame.MAXIMIZED_BOTH == 0
            if (normal || System.currentTimeMillis() - started > 1_000) {
                stop()
                Diagnostics.event("fullscreen: window restored from maximized after ${System.currentTimeMillis() - started} ms")
                action()
            }
        }
    }.start()
}

/** Logs when AWT's idea of the frame's bounds differs from the native window 500 ms after leaving fullscreen. */
private fun checkAwtInSync(frame: java.awt.Frame) {
    javax.swing.Timer(500) {
        val native = nativeRect(frame) ?: return@Timer
        val scale = frame.graphicsConfiguration.defaultTransform.scaleX
        val b = frame.bounds
        val awt = Rectangle(Math.round(b.x * scale).toInt(), Math.round(b.y * scale).toInt(), Math.round(b.width * scale).toInt(), Math.round(b.height * scale).toInt())
        val off = maxOf(kotlin.math.abs(awt.x - native.x), kotlin.math.abs(awt.y - native.y), kotlin.math.abs(awt.width - native.width), kotlin.math.abs(awt.height - native.height))
        if (off > 2) Diagnostics.event("fullscreen: AWT bounds ${awt.str()} differ from the native window ${native.str()} after leaving")
    }.apply { isRepeats = false }.start()
}

@OptIn(ExperimentalComposeUiApi::class) // ComposeDialog.setContent with key handlers, exceptionHandler
@Composable
private fun ControlsWindow(
    owner: ComposeWindow,
    anchor: Component,
    overlay: OverlayState,
    onPreviewKeyEvent: (KeyEvent) -> Boolean,
    content: @Composable () -> Unit,
) {
    val currentContent by rememberUpdatedState(content)
    val currentOnPreviewKeyEvent by rememberUpdatedState(onPreviewKeyEvent)
    val dialog = remember(owner) {
        ComposeDialog(owner, Dialog.ModalityType.MODELESS).apply {
            isUndecorated = true
            isTransparent = true
            isResizable = false
            title = "Controls"
            exceptionHandler = Diagnostics.windowExceptionHandlerFactory.exceptionHandler(this)
        }
    }

    // The frame's content area: AWT resizes it at once, while the canvas follows only after Compose's next layout (and came out
    // 2 px taller at 150 %, so a window on the canvas covered the frame's bottom border).
    val area: Component = owner.contentPane

    fun follow() {
        if (!area.isShowing) return
        val p: Point = area.locationOnScreen
        dialog.setBounds(p.x, p.y, area.width, area.height)
        // Lay the Compose content out for the new size now; without it the content kept the old size during a drag-resize.
        dialog.validate()
    }

    DisposableEffect(dialog, anchor) {
        Diagnostics.event("controls window: creating")
        dialog.setContent(onPreviewKeyEvent = { currentOnPreviewKeyEvent(it) }) { currentContent() }
        val listener = object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent) = follow()
            override fun componentResized(e: ComponentEvent) = follow()
            override fun componentShown(e: ComponentEvent) = follow()
        }
        owner.addComponentListener(listener)
        area.addComponentListener(listener)
        anchor.addComponentListener(listener)
        follow()
        GeometryProbe.dialog = dialog
        dialog.isVisible = true
        Diagnostics.event("controls window: shown")
        onDispose {
            owner.removeComponentListener(listener)
            area.removeComponentListener(listener)
            anchor.removeComponentListener(listener)
            dialog.dispose()
            Diagnostics.event("controls window: disposed")
        }
    }

    // Measures how well the controls window follows the video: compares AWT bounds and the native window rectangles every 250 ms
    // and logs each stretch of misalignment. It does not correct anything, so what the user sees is what the listeners achieve.
    LaunchedEffect(dialog, anchor) {
        var misalignedSince = 0L
        while (true) {
            delay(250)
            if (!area.isShowing || !dialog.isVisible) continue
            val p = area.locationOnScreen
            val canvas = Rectangle(p.x, p.y, area.width, area.height)
            val canvasNative = null as Rectangle?
            val dialogNative = nativeRect(dialog)
            val content = overlay.contentSizePx
            val contentFits = content == null || dialogNative == null ||
                (kotlin.math.abs(content.width - dialogNative.width) <= 1 && kotlin.math.abs(content.height - dialogNative.height) <= 1)
            if (!contentFits) Diagnostics.event("controls content laid out for ${content?.width}x${content?.height}, window is ${dialogNative?.width}x${dialogNative?.height}")
            val aligned = dialog.bounds == canvas && contentFits
            overlay.alignmentSamples++
            if (!aligned) {
                overlay.misalignedSamples++
                if (misalignedSince == 0L) {
                    misalignedSince = System.currentTimeMillis()
                    Diagnostics.event(
                        "controls window misaligned: dialog=${dialog.bounds.str()} canvas=${canvas.str()} " +
                            "native dialog=${dialogNative?.str()} canvas=${canvasNative?.str()}",
                    )
                }
            } else if (misalignedSince != 0L) {
                Diagnostics.event("controls window aligned again after ${System.currentTimeMillis() - misalignedSince} ms")
                misalignedSince = 0L
            }
        }
    }

    LaunchedEffect(dialog) {
        snapshotFlow { overlay.controlsVisible || !overlay.hideWindowWhenIdle }.collect { show ->
            if (show) follow()
            if (show && !dialog.isVisible) overlay.windowShows++
            dialog.isVisible = show
        }
    }
}

/** Heavyweight canvas whose native window (HWND on Windows) is handed to mpv as `wid`. */
class VideoCanvas(
    private val onPeerCreated: (Long) -> Unit,
    private val onPeerDestroying: () -> Unit,
    private val overlay: OverlayState,
    private val onDoubleClick: () -> Unit,
) : Canvas() {
    init {
        background = java.awt.Color.BLACK
        // Only reached when the controls window is hidden (and if mpv's disabled child window passes the mouse on).
        val mouse = object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) = overlay.pointerMoved()
            override fun mouseClicked(e: MouseEvent) {
                overlay.activity()
                if (e.clickCount == 2) onDoubleClick()
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
        addPropertyChangeListener("graphicsConfiguration") { e ->
            Diagnostics.event("canvas graphicsConfiguration: ${describe(e.oldValue)} -> ${describe(e.newValue)}")
        }
    }

    override fun addNotify() {
        super.addNotify()
        val wid = Native.getComponentID(this)
        Diagnostics.event("canvas native window created: $wid")
        // addNotify runs under AWT's tree lock; start mpv after it returns.
        SwingUtilities.invokeLater { if (isDisplayable) onPeerCreated(Native.getComponentID(this)) }
    }

    override fun removeNotify() {
        Diagnostics.event("canvas native window destroying")
        onPeerDestroying()
        super.removeNotify()
    }

    // mpv paints the whole area through its child window; painting here would only flicker.
    override fun paint(g: Graphics) = Unit
    override fun update(g: Graphics) = Unit
}

/** Logs window moves between screens, state changes and resizes (at most every 500 ms) to the event trail. */
private class WindowEventLogger(private val window: ComposeWindow) {
    private var lastResizeLog = 0L
    private val component = object : ComponentAdapter() {
        override fun componentResized(e: ComponentEvent) {
            val now = System.currentTimeMillis()
            if (now - lastResizeLog > 500) {
                lastResizeLog = now
                Diagnostics.event("window resized: ${window.width}x${window.height}")
            }
        }
    }
    private val state = java.awt.event.WindowStateListener { e -> Diagnostics.event("window state: ${e.oldState} -> ${e.newState}") }
    private val gc = java.beans.PropertyChangeListener { e ->
        Diagnostics.event("window graphicsConfiguration: ${describe(e.oldValue)} -> ${describe(e.newValue)}")
    }

    init {
        window.addComponentListener(component)
        window.addWindowStateListener(state)
        window.addPropertyChangeListener("graphicsConfiguration", gc)
        Diagnostics.event("window shown on ${describe(window.graphicsConfiguration)}")
    }

    fun remove() {
        window.removeComponentListener(component)
        window.removeWindowStateListener(state)
        window.removePropertyChangeListener("graphicsConfiguration", gc)
    }
}

private fun Rectangle.str() = "${width}x$height@$x,$y"

private fun describe(value: Any?): String {
    val gc = value as? java.awt.GraphicsConfiguration ?: return value.toString()
    val scale = gc.defaultTransform.scaleX
    return "${gc.device.iDstring} ${gc.bounds.width}x${gc.bounds.height}@${gc.bounds.x},${gc.bounds.y} scale=$scale"
}

val blankCursor: Cursor = Toolkit.getDefaultToolkit()
    .createCustomCursor(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "blank")
