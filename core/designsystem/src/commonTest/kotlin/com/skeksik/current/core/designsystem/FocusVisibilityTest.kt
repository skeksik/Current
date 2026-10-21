package com.skeksik.current.core.designsystem

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FocusVisibilityTest {
    @Test
    fun beforeAnyInputNothingCountsAsKeyboard() {
        val visibility = FocusVisibility()
        assertFalse(visibility.keyboard)
        assertFalse(visibility.showsRing(visibility.focusGained()))
        visibility.onKey(Key.Tab)
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
        // Tab, a key too, then moves focus on, and the element it reaches shows its ring.
        visibility.onKey(Key.Tab)
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

    @Test
    fun leavingTouchModeCountsAsKeyboardUntilTheNextPress() {
        val visibility = FocusVisibility()
        visibility.onPointerPress()
        // Android's first Tab after a tap: no key event, but focus arrives by keyboard.
        visibility.onKeyboardNavigation()
        val arrival = visibility.focusGained()
        assertTrue(visibility.showsRing(arrival))
        // The next tap takes the ring away, as after a key.
        visibility.onPointerPress()
        assertFalse(visibility.showsRing(arrival))
        assertFalse(visibility.showsRing(visibility.focusGained()))
    }
}
