package com.skeksik.current

import androidx.compose.foundation.isSystemInDarkTheme
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
        val input = remember { MainWindowInput() }
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
            AppContent(input)
        }
    }
}
