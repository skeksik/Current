package com.skeksik.current

import java.awt.event.MouseEvent
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals

class BackButtonListenerTest {
    private var backs = 0
    private val listener = BackButtonListener { backs++ }

    @Test
    fun theBackButtonGoesBackOncePerClick() {
        listener.mousePressed(event(MouseEvent.MOUSE_PRESSED, button = 4))
        listener.mouseReleased(event(MouseEvent.MOUSE_RELEASED, button = 4))
        listener.mouseClicked(event(MouseEvent.MOUSE_CLICKED, button = 4))
        assertEquals(1, backs)
    }

    @Test
    fun otherButtonsDoNothing() {
        listOf(MouseEvent.BUTTON1, MouseEvent.BUTTON2, MouseEvent.BUTTON3, 5).forEach {
            listener.mousePressed(event(MouseEvent.MOUSE_PRESSED, button = it))
        }
        assertEquals(0, backs)
    }

    private fun event(id: Int, button: Int) = MouseEvent(JPanel(), id, 0L, 0, 10, 10, 1, false, button)
}
