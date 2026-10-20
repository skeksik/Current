package com.skeksik.current.core.network

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Android callback's decisions, with strings standing in for network handles. */
class DefaultNetworkTrackerTest {
    @Test
    fun theNetworkAtStartIsNotAChange() {
        val tracker = DefaultNetworkTracker("wifi")
        assertFalse(tracker.available("wifi"))
        assertFalse(tracker.blockedStatus("wifi", false))
    }

    @Test
    fun theFirstConnectionAfterStartingOfflineIsAChange() {
        assertTrue(DefaultNetworkTracker<String>(null).available("wifi"))
    }

    @Test
    fun anotherDefaultNetworkOrLosingItIsAChange() {
        val tracker = DefaultNetworkTracker("wifi")
        assertTrue(tracker.available("other-wifi"))
        assertFalse(tracker.lost("wifi"), "a network that is no longer the default")
        assertTrue(tracker.lost("other-wifi"))
    }

    @Test
    fun theDefaultNetworkUnblockedInTheForegroundIsAChange() {
        val tracker = DefaultNetworkTracker("wifi")
        assertFalse(tracker.blockedStatus("wifi", true), "blocked in the background")
        assertTrue(tracker.blockedStatus("wifi", false), "usable again in the foreground")
        assertFalse(tracker.blockedStatus("wifi", false))
    }

    @Test
    fun aSwitchWhileBlockedIsAChangeAndSoIsTheUnblockAfterIt() {
        val tracker = DefaultNetworkTracker("wifi")
        tracker.blockedStatus("wifi", true)
        assertTrue(tracker.available("other-wifi"))
        assertFalse(tracker.blockedStatus("other-wifi", true), "reported blocked on arrival")
        assertTrue(tracker.blockedStatus("other-wifi", false))
    }

    @Test
    fun blockingAnotherNetworkIsIgnored() {
        val tracker = DefaultNetworkTracker("wifi")
        assertFalse(tracker.blockedStatus("vpn", true))
        assertFalse(tracker.blockedStatus("vpn", false))
    }
}
