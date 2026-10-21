package com.skeksik.current

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.skeksik.current.core.model.AppInfo
import java.awt.Dimension
import org.jetbrains.skia.Image

fun main() = application {
    // Koin around the windows, not inside one: the video window (ADR 0010) will join the same graph.
    CurrentKoin {
        val input = remember { MainWindowInput() }
        val icon = remember { appIcon() }
        Window(
            onCloseRequest = ::exitApplication,
            // The product name, the same in every language; not a string resource.
            title = AppInfo.NAME,
            icon = icon,
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

/** The installer's PNG (desktopApp/icons, on the classpath) for the title bar; without it the window shows a generic icon. */
private fun appIcon(): Painter? =
    Thread.currentThread().contextClassLoader.getResourceAsStream("current.png")?.use {
        BitmapPainter(Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap())
    }
