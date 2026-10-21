package com.skeksik.current.ui.shell

import androidx.compose.runtime.Stable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventHandler
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventInput

/**
 * The Back keys and buttons the window gets, whatever has focus: Alt+Left everywhere, and the mouse back button on desktop. The window
 * feeds them here (the app's `MainWindowInput`), and they go to the window's navigation events, where a system or remote Back goes: an
 * open dialog or menu takes them first, else the shell goes one step back.
 *
 * Esc is not a Back key: it closes dialogs, menus and other transient UI only. On desktop, Compose turns an Esc that nothing handled into
 * a back event of the window ([windowBackIsEscape], docs/platform-notes.md "Esc on desktop"), so the shell takes those itself
 * ([ShellBackHandler]) and does nothing with them.
 */
@Stable
class BackInput(
    /** Whether the window's own back events come only from Esc, as on desktop. Android's come from the system or remote Back. */
    val windowBackIsEscape: Boolean,
) {
    private val input = Input()
    private var dispatcher: NavigationEventDispatcher? = null

    /** Whether the back event being handled came from here, rather than from the window. */
    internal var fromBackKey = false
        private set

    /** Handles Alt+Left; true when it went back (or closed a dialog), so the window consumes the key. */
    fun onKeyEvent(event: KeyEvent): Boolean = isBackKey(event) && back()

    /** The mouse back button was pressed. */
    fun onBackButton(): Boolean = back()

    private fun back(): Boolean {
        if (dispatcher == null) return false
        fromBackKey = true
        try {
            input.completeBack()
        } finally {
            fromBackKey = false
        }
        return true
    }

    internal fun attach(to: NavigationEventDispatcher) {
        to.addInput(input)
        dispatcher = to
    }

    internal fun detach(from: NavigationEventDispatcher) {
        from.removeInput(input)
        if (dispatcher === from) dispatcher = null
    }

    private class Input : NavigationEventInput() {
        fun completeBack() = dispatchOnBackCompleted()
    }
}

internal fun isBackKey(event: KeyEvent): Boolean =
    isBackKey(event.type, event.key, event.isAltPressed, event.isCtrlPressed, event.isShiftPressed, event.isMetaPressed)

/** Alt+Left on its own, as it goes down: Alt with Ctrl, Shift or Meta is another shortcut. */
internal fun isBackKey(type: KeyEventType, key: Key, alt: Boolean, ctrl: Boolean, shift: Boolean, meta: Boolean): Boolean =
    type == KeyEventType.KeyDown && key == Key.DirectionLeft && alt && !ctrl && !shift && !meta

/**
 * The shell's back handler where the window's back events are Esc ([BackInput.windowBackIsEscape]). Added after NavHost's own handler,
 * so it is newer and gets back events first (handlers of one priority are called newest first); a dialog or menu that opens later is
 * newer still and gets them before it. It goes [back] for [input]'s keys and buttons only, so Esc never goes back.
 */
internal class ShellBackHandler(private val input: BackInput, private val back: () -> Unit) :
    NavigationEventHandler<NavigationEventInfo>(NavigationEventInfo.None, isBackEnabled = true) {
    override fun onBackCompleted() {
        if (input.fromBackKey) back()
    }
}
