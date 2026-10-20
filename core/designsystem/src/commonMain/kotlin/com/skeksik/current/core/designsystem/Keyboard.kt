package com.skeksik.current.core.designsystem

import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import com.skeksik.current.core.designsystem.CurrentTokens.Size

// Every screen works with the keyboard alone (and later a remote): visible focus, arrows and Tab move it, Enter acts.

/** Draws a ring in the accent colour while this element or one inside it has focus. Put it before the focusable element's own modifiers. */
@Composable
fun Modifier.focusRing(shape: Shape): Modifier {
    var focused by remember { mutableStateOf(false) }
    val color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent
    return onFocusChanged { focused = it.hasFocus }.border(Size.focusRing, color, shape)
}

/**
 * Focus within one vertical list: Up and Down move to the previous or next item of this list only, never to other content. At the list's
 * ends the keys are left alone. Put [arrowKeys] on the list and [item] on each item; an item may hold several focusable elements (a row
 * with buttons), and moving to it focuses its first.
 */
class ListFocus internal constructor(size: Int) {
    internal val requesters = List(size) { FocusRequester() }
    internal var current by mutableStateOf(-1)

    /** Marks item [index] of the list. */
    fun Modifier.item(index: Int): Modifier =
        focusRequester(requesters[index]).onFocusChanged { if (it.hasFocus) current = index }

    /** Handles Up and Down for the list; put it on the list's container. */
    fun Modifier.arrowKeys(): Modifier = onPreviewKeyEvent { event ->
        val target = when (event.key) {
            Key.DirectionDown -> current + 1
            Key.DirectionUp -> current - 1
            else -> return@onPreviewKeyEvent false
        }
        if (current < 0 || target !in requesters.indices) return@onPreviewKeyEvent false
        if (event.type == KeyEventType.KeyDown) requesters[target].requestFocus()
        true
    }
}

/** A [ListFocus] for a list of [size] items. */
@Composable
fun rememberListFocus(size: Int): ListFocus = remember(size) { ListFocus(size) }

/**
 * For single-line text fields: Enter (or the keypad's Enter) runs [action], and Tab and Shift+Tab move focus instead of typing a tab.
 */
@Composable
fun Modifier.formField(action: () -> Unit): Modifier {
    val focus = LocalFocusManager.current
    return onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            Key.Enter, Key.NumPadEnter -> {
                action()
                true
            }
            Key.Tab -> focus.moveFocus(if (event.isShiftPressed) FocusDirection.Previous else FocusDirection.Next)
            else -> false
        }
    }
}
