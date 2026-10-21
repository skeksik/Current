package com.skeksik.current.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MinimumInteractiveLeftAlignmentLine
import androidx.compose.material3.MinimumInteractiveTopAlignmentLine
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size as GeometrySize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Dp
import com.skeksik.current.core.designsystem.CurrentTokens.Size

// Every screen works with the keyboard alone (and later a remote): visible focus, arrows and Tab move it, Enter acts.

/**
 * Whether an element that gains focus shows a ring: yes when focus arrives after a key press (Tab, an arrow, Enter opening a page that
 * focuses its first field), no when it arrives after a pointer press, as a click focuses what it hits. The ring stays until the next
 * pointer press; a key pressed on an element focused by a click does not bring it up. Starts as after a pointer press: nothing has focus
 * until the user moves it (the first Tab), and pages place focus themselves only after keyboard input ([keyboard]). One per window, from
 * [LocalFocusVisibility]; [trackFocusVisibility] keeps it up to date.
 */
@Stable
class FocusVisibility {
    /**
     * Whether the last press was a key rather than a pointer. A plain field, not snapshot state: read it when deciding (an element
     * gaining focus, a page placing focus), as a composable reading it would not recompose when it changes.
     */
    var keyboard = false
        private set

    /** Pointer presses so far: a ring shown since an earlier count has gone. Snapshot state, so drawing follows it. */
    internal var pointerPresses by mutableIntStateOf(0)
        private set

    /** A key went down. Modifier keys alone (Ctrl held for a Ctrl+click) say nothing about how the user moves focus. */
    fun onKey(key: Key) {
        if (key !in modifierKeys) keyboard = true
    }

    fun onKeyEvent(event: KeyEvent) {
        if (event.type == KeyEventType.KeyDown) onKey(event.key)
    }

    /**
     * Keyboard input the window saw but no key event carried. Android leaves touch mode on the first navigation key after a tap (Tab,
     * an arrow, Enter): it consumes that key, so the activity never gets it, and focuses the first focusable element itself.
     */
    fun onKeyboardNavigation() {
        keyboard = true
    }

    /** A mouse button, a finger or a pen went down. */
    fun onPointerPress() {
        keyboard = false
        pointerPresses++
    }

    /** Taken when an element gains focus; pass it to [showsRing] while the element keeps focus. */
    internal fun focusGained(): FocusArrival = FocusArrival(byKeyboard = keyboard, pointerPresses = pointerPresses)

    /** Whether an element focused as [arrival] shows its ring now. */
    internal fun showsRing(arrival: FocusArrival): Boolean = arrival.byKeyboard && arrival.pointerPresses == pointerPresses

    private companion object {
        val modifierKeys = setOf(
            Key.ShiftLeft, Key.ShiftRight, Key.CtrlLeft, Key.CtrlRight, Key.AltLeft, Key.AltRight, Key.MetaLeft, Key.MetaRight,
        )
    }
}

/** How an element got focus, from [FocusVisibility.focusGained]. */
internal data class FocusArrival(val byKeyboard: Boolean, val pointerPresses: Int)

/** The window's [FocusVisibility]. Each window provides its own; the default serves previews and tests. */
val LocalFocusVisibility = staticCompositionLocalOf { FocusVisibility() }

/**
 * Feeds key and pointer presses inside this element to [LocalFocusVisibility], without consuming them. Put it on the root of a window and
 * of each dialog (a dialog gets its input apart from the window). Keys reach it only while something inside has focus, so the window
 * forwards its own key events too, and the first key after a click or tap on empty space counts: desktop's `Window(onPreviewKeyEvent)`
 * and Android's `Activity.dispatchKeyEvent`, through the app's `MainWindowInput`. On Android the first Tab after a tap never gets that
 * far (it only leaves touch mode), so the activity also reports leaving touch mode ([FocusVisibility.onKeyboardNavigation]).
 */
@Composable
fun Modifier.trackFocusVisibility(): Modifier {
    val visibility = LocalFocusVisibility.current
    return onPreviewKeyEvent {
        visibility.onKeyEvent(it)
        false
    }.pointerInput(visibility) {
        awaitPointerEventScope {
            while (true) {
                if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) visibility.onPointerPress()
            }
        }
    }
}

/**
 * Draws a ring in the accent colour while this element or one inside it has focus that arrived by keyboard ([FocusVisibility]). The
 * ring is drawn outside the element's visible shape, [gap] away from its edge, so it shows on a filled button of the same colour, and it
 * takes no layout space. Material buttons pad themselves to the minimum touch target; the ring follows the visible button inside that
 * padding, so the gap is even on every side. Put it before the focusable element's own modifiers, before any clip.
 */
@Composable
fun Modifier.focusRing(shape: Shape, gap: Dp = Size.focusRingGap): Modifier =
    this then FocusRingElement(shape, gap, MaterialTheme.colorScheme.primary, LocalFocusVisibility.current)

private data class FocusRingElement(val shape: Shape, val gap: Dp, val color: Color, val visibility: FocusVisibility) :
    ModifierNodeElement<FocusRingNode>() {
    override fun create() = FocusRingNode(shape, gap, color, visibility)

    override fun update(node: FocusRingNode) {
        node.shape = shape
        node.gap = gap
        node.color = color
        node.visibility = visibility
        node.invalidateDraw()
    }
}

private class FocusRingNode(var shape: Shape, var gap: Dp, var color: Color, var visibility: FocusVisibility) :
    Modifier.Node(), FocusEventModifierNode, LayoutModifierNode, DrawModifierNode {
    // Null while unfocused.
    private var arrival: FocusArrival? = null

    // Where the visible element starts inside its touch target padding (MinimumInteractiveModifier publishes these lines); 0 without one.
    private var insetX = 0
    private var insetY = 0

    override fun onFocusEvent(focusState: FocusState) {
        if ((arrival != null) != focusState.hasFocus) {
            arrival = if (focusState.hasFocus) visibility.focusGained() else null
            invalidateDraw()
        }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        insetX = placeable[MinimumInteractiveLeftAlignmentLine].takeUnless { it == AlignmentLine.Unspecified } ?: 0
        insetY = placeable[MinimumInteractiveTopAlignmentLine].takeUnless { it == AlignmentLine.Unspecified } ?: 0
        return layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        // Reading the pointer presses here redraws the ring when a click takes it away.
        val shown = arrival ?: return
        if (!visibility.showsRing(shown)) return
        val stroke = Size.focusRing.toPx()
        val outset = gap.toPx() + stroke / 2
        val visible = GeometrySize(size.width - 2 * insetX, size.height - 2 * insetY)
        val outline = shape.createOutline(GeometrySize(visible.width + 2 * outset, visible.height + 2 * outset), layoutDirection, this)
        translate(insetX - outset, insetY - outset) { drawOutline(outline, color, style = Stroke(stroke)) }
    }
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
