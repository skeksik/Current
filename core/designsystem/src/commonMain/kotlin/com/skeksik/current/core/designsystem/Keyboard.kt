package com.skeksik.current.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MinimumInteractiveLeftAlignmentLine
import androidx.compose.material3.MinimumInteractiveTopAlignmentLine
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Dp
import com.skeksik.current.core.designsystem.CurrentTokens.Size

// Every screen works with the keyboard alone (and later a remote): visible focus, arrows and Tab move it, Enter acts.

/**
 * Draws a ring in the accent colour while this element or one inside it has focus. The ring is drawn outside the element's visible shape,
 * [gap] away from its edge, so it shows on a filled button of the same colour, and it takes no layout space. Material buttons pad
 * themselves to the minimum touch target; the ring follows the visible button inside that padding, so the gap is even on every side. Put
 * it before the focusable element's own modifiers, before any clip.
 */
@Composable
fun Modifier.focusRing(shape: Shape, gap: Dp = Size.focusRingGap): Modifier =
    this then FocusRingElement(shape, gap, MaterialTheme.colorScheme.primary)

private data class FocusRingElement(val shape: Shape, val gap: Dp, val color: Color) : ModifierNodeElement<FocusRingNode>() {
    override fun create() = FocusRingNode(shape, gap, color)

    override fun update(node: FocusRingNode) {
        node.shape = shape
        node.gap = gap
        node.color = color
        node.invalidateDraw()
    }
}

private class FocusRingNode(var shape: Shape, var gap: Dp, var color: Color) :
    Modifier.Node(), FocusEventModifierNode, LayoutModifierNode, DrawModifierNode {
    private var focused = false

    // Where the visible element starts inside its touch target padding (MinimumInteractiveModifier publishes these lines); 0 without one.
    private var insetX = 0
    private var insetY = 0

    override fun onFocusEvent(focusState: FocusState) {
        if (focused != focusState.hasFocus) {
            focused = focusState.hasFocus
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
        if (!focused) return
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
