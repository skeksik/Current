package com.skeksik.current

import java.awt.event.MouseEvent
import kotlin.test.Test
import kotlin.test.assertEquals

// Buttons are passed as numbers: a MouseEvent for button 4 or 5 cannot be made on a headless test machine (AWT checks MouseInfo).
class BackButtonListenerTest {
    private var backs = 0
    private val listener = BackButtonListener { backs++ }

    @Test
    fun theBackButtonGoesBack() {
        listener.onPressed(4)
        assertEquals(1, backs)
    }

    @Test
    fun otherButtonsDoNothing() {
        listOf(MouseEvent.BUTTON1, MouseEvent.BUTTON2, MouseEvent.BUTTON3, 5).forEach(listener::onPressed)
        assertEquals(0, backs)
    }
}
