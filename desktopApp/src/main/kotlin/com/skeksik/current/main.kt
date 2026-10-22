package com.skeksik.current

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.skeksik.current.core.database.AppFolders
import com.skeksik.current.core.model.AppInfo
import java.awt.Dimension
import java.io.IOException
import javax.swing.JOptionPane
import kotlin.system.exitProcess
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.jetbrains.skia.Image

fun main() {
    // Before Koin, so a second launch never opens the settings files (docs/architecture.md, "Single instance (desktop)").
    val activations = claimSingleInstance()
    app(activations)
}

/** The app's claim on this machine, or exits when another process holds it. Emits each time a later launch asks to come forward. */
private fun claimSingleInstance(): SharedFlow<Unit> {
    val activations = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val claim = try {
        FileLockSingleInstance(AppFolders.local(), beforeSignal = WindowsForeground::allow).claim { activations.tryEmit(Unit) }
    } catch (e: IOException) {
        // The folder cannot hold the lock: starting unguarded beats not starting at all.
        System.err.println("Current: no single-instance check (${e.javaClass.simpleName})")
        return activations
    }
    when (claim) {
        // application() ends the process with exitProcess, so the claim is released by a shutdown hook.
        is Claim.Primary -> Runtime.getRuntime().addShutdownHook(Thread(claim::close))
        Claim.Signalled -> exitProcess(0)
        Claim.NotReached -> {
            // A GUI app's stderr is not seen, so say it on screen. Plain Swing: nothing of the app has started.
            val message = if (System.getProperty("os.name").startsWith("Windows")) NOT_RESPONDING_WINDOWS else NOT_RESPONDING
            JOptionPane.showMessageDialog(null, message, AppInfo.NAME, JOptionPane.WARNING_MESSAGE)
            exitProcess(1)
        }
    }
    return activations
}

/** Shown before Koin and Compose start, so not a string resource; English only until the app has more languages. */
private const val NOT_RESPONDING_WINDOWS = "Current is already running but isn't responding. Close it in Task Manager and try again."
private const val NOT_RESPONDING = "Current is already running but isn't responding. Close it and try again."

private fun app(activations: SharedFlow<Unit>) = application {
    // Koin around the windows, not inside one: the video window (ADR 0010) will join the same graph.
    CurrentKoin {
        // Compose desktop turns an Esc nothing handled into the window's back event (docs/platform-notes.md, "Esc on desktop").
        val input = remember { MainWindowInput(escapeIsWindowBack = true) }
        val icon = remember { appIcon() }
        val windowState = rememberWindowState(width = 1200.dp, height = 780.dp)
        Window(
            onCloseRequest = ::exitApplication,
            // The product name, the same in every language; not a string resource.
            title = AppInfo.NAME,
            icon = icon,
            state = windowState,
            onPreviewKeyEvent = input::onPreviewKeyEvent,
        ) {
            LaunchedEffect(Unit) { window.minimumSize = Dimension(760, 520) }
            // A later launch of the app: restore this window and bring it forward instead.
            LaunchedEffect(Unit) {
                activations.collect {
                    windowState.isMinimized = false
                    window.toFront()
                    window.requestFocus()
                }
            }
            // The app follows the system theme (CurrentTheme), and so does the title bar.
            val dark = isSystemInDarkTheme()
            LaunchedEffect(dark) { WindowsTitleBar.setDark(window, dark) }
            // On the window, not in the content: an open dialog's layer takes Compose's pointer events (BackButtonListener).
            DisposableEffect(window) {
                val backButton = BackButtonListener(input::onBackButton)
                window.addMouseListener(backButton)
                onDispose { window.removeMouseListener(backButton) }
            }
            AppContent(input)
        }
    }
}

/** The installer's PNG (desktopApp/icons, on the classpath) for the title bar; without it the window shows a generic icon. */
private fun appIcon(): Painter? =
    Thread.currentThread().contextClassLoader.getResourceAsStream("current.png")?.use {
        BitmapPainter(Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap())
    }
