package com.skeksik.current.core.designsystem

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FocusVisibilityTest {
    @Test
    fun focusPlacedBeforeAnyInputShowsTheRing() {
        val visibility = FocusVisibility()
        assertTrue(visibility.showsRing(visibility.focusGained()))
    }

    @Test
    fun focusThatArrivesByKeyboardShowsTheRingUntilTheNextClick() {
        val visibility = FocusVisibility()
        visibility.onPointerPress()
        visibility.onKey(Key.Tab)
        val arrival = visibility.focusGained()
        assertTrue(visibility.showsRing(arrival))
        visibility.onPointerPress()
        assertFalse(visibility.showsRing(arrival))
        visibility.onKey(Key.Tab)
        assertFalse(visibility.showsRing(arrival))
    }

    @Test
    fun aKeyPressedOnAnElementFocusedByAClickLeavesItWithoutARing() {
        val visibility = FocusVisibility()
        visibility.onPointerPress()
        val arrival = visibility.focusGained()
        assertFalse(visibility.showsRing(arrival))
        visibility.onKey(Key.Escape)
        assertFalse(visibility.showsRing(arrival))
        // Tab then moves focus on, and the next element shows its ring.
        assertTrue(visibility.showsRing(visibility.focusGained()))
    }

    @Test
    fun modifierKeysAloneDoNotCountAsKeyboardInput() {
        val visibility = FocusVisibility()
        visibility.onPointerPress()
        listOf(Key.CtrlLeft, Key.ShiftRight, Key.AltLeft, Key.MetaLeft).forEach(visibility::onKey)
        assertFalse(visibility.showsRing(visibility.focusGained()))
        visibility.onKey(Key.DirectionDown)
        assertTrue(visibility.showsRing(visibility.focusGained()))
    }
}
