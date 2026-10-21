package com.skeksik.current.core.network

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DesktopLogEchoTest {
    @Test
    fun printsOnlyWhenAFlagSaysDebug() {
        assertNull(desktopLogEcho(null, null))
        assertNull(desktopLogEcho("info", ""))
        assertNotNull(desktopLogEcho("debug", null))
        assertNotNull(desktopLogEcho(null, " DEBUG "))
    }
}
