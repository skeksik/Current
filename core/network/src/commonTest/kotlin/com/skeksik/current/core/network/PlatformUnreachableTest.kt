package com.skeksik.current.core.network

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformUnreachableTest {
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

    @Test
    fun ktorsFetchFailureMatches() {
        assertTrue(isKtorFetchFailure("Fail to fetch"))
    }

    @Test
    fun otherErrorsAreNotFetchFailures() {
        assertFalse(isKtorFetchFailure(null))
        assertFalse(isKtorFetchFailure("Failed to fetch"), "the browser's own TypeError is the cause, not the error Ktor throws")
        assertFalse(isKtorFetchFailure("Cannot read properties of undefined"))
    }
}
