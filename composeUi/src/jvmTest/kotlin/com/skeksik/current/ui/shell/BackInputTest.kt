package com.skeksik.current.ui.shell

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventHandler
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A handler like NavHost's or a dialog's: counts the back events it gets. */
private class Counting : NavigationEventHandler<NavigationEventInfo>(NavigationEventInfo.None, isBackEnabled = true) {
    var backs = 0

    override fun onBackCompleted() {
        backs++
    }
}

/** What Compose desktop adds to each window: an Esc nothing handled completes a back (`BackNavigationEventInput`). */
private class Escape : NavigationEventInput() {
    fun press() = dispatchOnBackCompleted()
}

class BackInputTest {
    private val dispatcher = NavigationEventDispatcher()
    private val escape = Escape().also(dispatcher::addInput)

    // In the order the shell composes them: NavHost's handler first, the shell's after it.
    private val navHost = Counting().also(dispatcher::addHandler)

    @Test
    fun onDesktopEscNeverGoesBackButTheBackKeysDo() {
        var wentBack = 0
        val input = desktopShell { wentBack++ }

        escape.press()
        assertEquals(0, wentBack)
        assertEquals(0, navHost.backs)

        input.onBackButton()
        assertEquals(1, wentBack)
        assertEquals(0, navHost.backs)
    }

    @Test
    fun onDesktopAnOpenDialogTakesEscAndTheBackKeysFirst() {
        var wentBack = 0
        val input = desktopShell { wentBack++ }
        // Opened after the shell was composed, as a dialog or menu always is.
        val dialog = Counting().also(dispatcher::addHandler)

        escape.press()
        input.onBackButton()
        assertEquals(2, dialog.backs)
        assertEquals(0, wentBack)

        dialog.remove()
        escape.press()
        input.onBackButton()
        assertEquals(2, dialog.backs)
        assertEquals(1, wentBack)
    }

    @Test
    fun onAndroidTheBackKeysReachNavHostLikeTheSystemBack() {
        val input = BackInput(windowBackIsEscape = false).also { it.attach(dispatcher) }

        input.onBackButton()
        assertEquals(1, navHost.backs)
    }

    @Test
    fun aDetachedInputConsumesNothing() {
        val input = BackInput(windowBackIsEscape = false).also { it.attach(dispatcher) }
        input.detach(dispatcher)

        assertEquals(false, input.onBackButton())
        assertEquals(0, navHost.backs)
    }

    @Test
    fun altLeftAloneIsTheBackKey() {
        assertTrue(isBackKey(KeyEventType.KeyDown, Key.DirectionLeft, alt = true, ctrl = false, shift = false, meta = false))
        assertFalse(isBackKey(KeyEventType.KeyUp, Key.DirectionLeft, alt = true, ctrl = false, shift = false, meta = false))
        assertFalse(isBackKey(KeyEventType.KeyDown, Key.DirectionLeft, alt = false, ctrl = false, shift = false, meta = false))
        assertFalse(isBackKey(KeyEventType.KeyDown, Key.DirectionLeft, alt = true, ctrl = false, shift = true, meta = false))
        assertFalse(isBackKey(KeyEventType.KeyDown, Key.Escape, alt = false, ctrl = false, shift = false, meta = false))
    }

    private fun desktopShell(back: () -> Unit): BackInput {
        val input = BackInput(windowBackIsEscape = true).also { it.attach(dispatcher) }
        dispatcher.addHandler(ShellBackHandler(input, back))
        return input
    }
}
