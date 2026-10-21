package com.skeksik.current

import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

/**
 * Desktop: calls [onBack] when the mouse back button (X1, AWT's button 4) goes down anywhere in the window, dialogs included. Add it with
 * `ComposeWindow.addMouseListener`, which Compose forwards to the one component that gets the window's mouse events, its dialog and
 * popup layers' too (docs/platform-notes.md, "Esc on desktop, and Back keys"). Compose's own pointer input would miss X1 over an open
 * dialog, whose layer takes the events. Only the press counts, so one click goes back once.
 */
class BackButtonListener(private val onBack: () -> Unit) : MouseAdapter() {
    override fun mousePressed(event: MouseEvent) {
        if (event.button == BACK_BUTTON) onBack()
    }

    private companion object {
        // What Compose maps to PointerButton.Back (ComposeSceneMediator.desktop.kt, composePointerButton).
        const val BACK_BUTTON = 4
    }
}
