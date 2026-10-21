package com.skeksik.current.core.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ListFocusTest {
    @Test
    fun upAndDownGoToTheNeighboursAndStopAtTheEnds() {
        val focus = ListFocus().apply { keys = listOf("a", "b", "c") }
        assertNull(focus.neighbour(1))
        focus.current = "b"
        assertEquals("a", focus.neighbour(-1))
        assertEquals("c", focus.neighbour(1))
        focus.current = "c"
        assertNull(focus.neighbour(1))
    }

    @Test
    fun anItemMovedWhileFocusedGoesOnFromWhereItIsNow() {
        val focus = ListFocus().apply { keys = listOf("a", "b", "c") }
        focus.current = "c"
        // Its own Move up button moved it, so focus never left it.
        focus.keys = listOf("a", "c", "b")
        assertEquals("a", focus.neighbour(-1))
        assertEquals("b", focus.neighbour(1))
    }

    @Test
    fun aRemovedItemLeavesTheKeysAlone() {
        val focus = ListFocus().apply { keys = listOf("a", "b") }
        focus.current = "b"
        focus.keys = listOf("a")
        assertNull(focus.neighbour(-1))
    }
}
