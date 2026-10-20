package com.skeksik.current

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension

fun main() = application {
    // Koin around the windows, not inside one: the video window (ADR 0010) will join the same graph.
    CurrentKoin {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Current",
            state = rememberWindowState(width = 1200.dp, height = 780.dp),
        ) {
            window.minimumSize = Dimension(760, 520)
            AppContent()
        }
    }
}
