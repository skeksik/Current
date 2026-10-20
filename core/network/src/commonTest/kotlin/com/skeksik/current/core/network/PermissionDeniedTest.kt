package com.skeksik.current.core.network

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PermissionDeniedTest {
    @Test
    fun androidsMissingInternetPermissionMessageMatches() {
        assertTrue(isMissingInternetPermission("Permission denied (missing INTERNET permission?)"))
    }

    @Test
    fun otherMessagesDoNot() {
        assertFalse(isMissingInternetPermission(null))
        assertFalse(isMissingInternetPermission("Permission Denial: reading content://media/external requires a grant"))
        assertFalse(isMissingInternetPermission("Permission denied"))
    }
}
