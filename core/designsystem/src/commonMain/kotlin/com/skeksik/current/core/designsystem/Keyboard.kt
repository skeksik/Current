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

/** Up and Down move focus within a vertical list, as Tab and Shift+Tab do. */
@Composable
fun Modifier.arrowKeyFocus(): Modifier {
    val focus = LocalFocusManager.current
    return onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            Key.DirectionDown -> focus.moveFocus(FocusDirection.Next)
            Key.DirectionUp -> focus.moveFocus(FocusDirection.Previous)
            else -> false
        }
    }
}

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
