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
        onKeyEvent = { event -> session.player?.let { handleKey(event, it, windowState, overlay) } ?: false },
    ) {
        DisposableEffect(window) {
            val listener = WindowEventLogger(window)
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
                    isFullscreen = { windowState.placement == WindowPlacement.Fullscreen },
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
                    onKeyEvent = { event -> handleKey(event, currentPlayer, windowState, overlay) },
                ) {
                    PlayerControls(
                        player = currentPlayer,
                        recorder = recorder,
                        title = displayName(request.source),
                        overlay = overlay,
                        isFullscreen = windowState.placement == WindowPlacement.Fullscreen,
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

fun toggleFullscreen(windowState: WindowState) {
    windowState.placement =
        if (windowState.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen
}

@OptIn(ExperimentalComposeUiApi::class) // ComposeDialog.setContent with key handlers, exceptionHandler
@Composable
private fun ControlsWindow(
    owner: ComposeWindow,
    anchor: Component,
    overlay: OverlayState,
    onKeyEvent: (KeyEvent) -> Boolean,
    content: @Composable () -> Unit,
) {
    val currentContent by rememberUpdatedState(content)
    val currentOnKeyEvent by rememberUpdatedState(onKeyEvent)
    val dialog = remember(owner) {
        ComposeDialog(owner, Dialog.ModalityType.MODELESS).apply {
            isUndecorated = true
            isTransparent = true
            title = "Controls"
            exceptionHandler = Diagnostics.windowExceptionHandlerFactory.exceptionHandler(this)
        }
    }

    fun follow() {
        if (!anchor.isShowing) return
        val p: Point = anchor.locationOnScreen
        dialog.setBounds(p.x, p.y, anchor.width, anchor.height)
    }

    DisposableEffect(dialog, anchor) {
        Diagnostics.event("controls window: creating")
        dialog.setContent(onKeyEvent = { currentOnKeyEvent(it) }) { currentContent() }
        val listener = object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent) = follow()
            override fun componentResized(e: ComponentEvent) = follow()
            override fun componentShown(e: ComponentEvent) = follow()
        }
        owner.addComponentListener(listener)
        anchor.addComponentListener(listener)
        follow()
        dialog.isVisible = true
        Diagnostics.event("controls window: shown")
        onDispose {
            owner.removeComponentListener(listener)
            anchor.removeComponentListener(listener)
            dialog.dispose()
            Diagnostics.event("controls window: disposed")
        }
    }

    LaunchedEffect(dialog) {
        snapshotFlow { overlay.controlsVisible || !overlay.hideWindowWhenIdle }.collect { show ->
            if (show) follow()
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
            override fun mouseMoved(e: MouseEvent) = overlay.activity()
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

private fun describe(value: Any?): String {
    val gc = value as? java.awt.GraphicsConfiguration ?: return value.toString()
    val scale = gc.defaultTransform.scaleX
    return "${gc.device.iDstring} ${gc.bounds.width}x${gc.bounds.height}@${gc.bounds.x},${gc.bounds.y} scale=$scale"
}

val blankCursor: Cursor = Toolkit.getDefaultToolkit()
    .createCustomCursor(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "blank")
