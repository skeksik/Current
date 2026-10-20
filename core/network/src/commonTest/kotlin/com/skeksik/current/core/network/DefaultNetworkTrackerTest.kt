package com.skeksik.current.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Android callback's decisions, with strings standing in for network handles. */
class DefaultNetworkTrackerTest {
    @Test
    fun theNetworkAtStartIsNotAChange() {
        val tracker = DefaultNetworkTracker("wifi")
        assertNull(tracker.available("wifi"))
        assertNull(tracker.blockedStatus("wifi", false))
    }

    @Test
    fun theFirstConnectionAfterStartingOfflineIsAChange() {
        assertEquals(NetworkChange.Changed, DefaultNetworkTracker<String>(null).available("wifi"))
    }

    @Test
    fun anotherDefaultNetworkOrLosingItIsAChange() {
        val tracker = DefaultNetworkTracker("wifi")
        assertEquals(NetworkChange.Changed, tracker.available("other-wifi"))
        assertNull(tracker.lost("wifi"), "a network that is no longer the default")
        assertEquals(NetworkChange.Changed, tracker.lost("other-wifi"))
    }

    @Test
    fun theSameNetworkUnblockedInTheForegroundResumesWithoutBeingAChange() {
        val tracker = DefaultNetworkTracker("wifi")
        assertNull(tracker.blockedStatus("wifi", true), "blocked in the background")
        assertEquals(NetworkChange.Resumed, tracker.blockedStatus("wifi", false), "usable again in the foreground")
        assertNull(tracker.blockedStatus("wifi", false))
    }

    @Test
    fun aSwitchWhileBlockedIsOneChangeNotTwo() {
        val tracker = DefaultNetworkTracker("wifi")
        tracker.blockedStatus("wifi", true)
        assertEquals(NetworkChange.Changed, tracker.available("other-wifi"))
        assertNull(tracker.blockedStatus("other-wifi", false), "the switch was the change; nothing was blocked on this network")
    }

    @Test
    fun aSwitchThatArrivesBlockedResumesOnceUnblocked() {
        val tracker = DefaultNetworkTracker("wifi")
        tracker.blockedStatus("wifi", true)
        assertEquals(NetworkChange.Changed, tracker.available("other-wifi"))
        assertNull(tracker.blockedStatus("other-wifi", true), "reported blocked on arrival")
        assertEquals(NetworkChange.Resumed, tracker.blockedStatus("other-wifi", false))
    }

    @Test
    fun blockingAnotherNetworkIsIgnored() {
        val tracker = DefaultNetworkTracker("wifi")
        assertNull(tracker.blockedStatus("vpn", true))
        assertNull(tracker.blockedStatus("vpn", false))
    }
}
