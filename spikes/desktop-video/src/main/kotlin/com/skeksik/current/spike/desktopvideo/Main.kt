package com.skeksik.current.spike.desktopvideo

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.application

/** `-Dspike.source=...` (and the other `spike.*` properties, see README) skips the launcher. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    Diagnostics.install()
    // The video canvas is painted by mpv; stop AWT from erasing it to the background colour on every resize.
    System.setProperty("sun.awt.noerasebackground", "true")
    val initialRequest = PlayRequest.fromSystemProperties()
    if (initialRequest?.selfTest == true) Diagnostics.exitOnWindowException = true

    application {
        CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides Diagnostics.windowExceptionHandlerFactory) {
            var request by remember { mutableStateOf(initialRequest) }
            var lastRequest by remember { mutableStateOf(initialRequest) }
            val current = request
            current?.let { Fullscreen.mode = it.fullscreen }
            if (current == null) {
                LauncherWindow(
                    initial = lastRequest,
                    onPlay = {
                        lastRequest = it
                        request = it
                    },
                    onCloseRequest = ::exitApplication,
                )
            } else if (current.engine == "texture") {
                TexturePlayerWindow(
                    request = current,
                    onClosed = { if (current.selfTest) exitApplication() else request = null },
                )
            } else {
                PlayerWindow(
                    request = current,
                    onClosed = { if (current.selfTest) exitApplication() else request = null },
                )
            }
        }
    }
}
