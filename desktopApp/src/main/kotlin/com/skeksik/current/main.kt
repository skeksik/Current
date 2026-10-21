package com.skeksik.current

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension

fun main() = application {
    // Koin around the windows, not inside one: the video window (ADR 0010) will join the same graph.
    CurrentKoin {
        // Compose desktop turns an Esc nothing handled into the window's back event (docs/platform-notes.md, "Esc on desktop").
        val input = remember { MainWindowInput(escapeIsWindowBack = true) }
        Window(
            onCloseRequest = ::exitApplication,
            // The product name, the same in every language (AppInfo.NAME); not a string resource.
            title = "Current",
            state = rememberWindowState(width = 1200.dp, height = 780.dp),
            onPreviewKeyEvent = input::onPreviewKeyEvent,
        ) {
            LaunchedEffect(Unit) { window.minimumSize = Dimension(760, 520) }
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
