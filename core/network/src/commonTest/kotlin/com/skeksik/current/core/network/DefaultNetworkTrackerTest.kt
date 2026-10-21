package com.skeksik.current.core.network

import kotlin.test.Test
import kotlin.test.assertEquals

/** The Android callback's decisions, with strings standing in for network handles. */
class DefaultNetworkTrackerTest {
    private val nothing = emptyList<NetworkChange>()
    private val changed = listOf(NetworkChange.Changed)
    private val suspended = listOf(NetworkChange.Suspended)
    private val resumed = listOf(NetworkChange.Resumed)

    @Test
    fun theNetworkAtStartIsNotAChange() {
        val tracker = DefaultNetworkTracker("wifi")
        assertEquals(nothing, tracker.available("wifi"))
        assertEquals(nothing, tracker.blockedStatus("wifi", false))
    }

    @Test
    fun theFirstConnectionAfterStartingOfflineIsAChange() {
        assertEquals(changed, DefaultNetworkTracker<String>(null).available("wifi"))
    }

    @Test
    fun anotherDefaultNetworkOrLosingItIsAChange() {
        val tracker = DefaultNetworkTracker("wifi")
        assertEquals(changed, tracker.available("other-wifi"))
        assertEquals(nothing, tracker.lost("wifi"), "a network that is no longer the default")
        assertEquals(changed, tracker.lost("other-wifi"))
    }

    @Test
    fun blockedInTheBackgroundSuspendsAndUnblockedResumes() {
        val tracker = DefaultNetworkTracker("wifi")
        assertEquals(suspended, tracker.blockedStatus("wifi", true), "blocked in the background")
        assertEquals(nothing, tracker.blockedStatus("wifi", true), "still blocked while frozen")
        assertEquals(resumed, tracker.blockedStatus("wifi", false), "usable again in the foreground")
        assertEquals(nothing, tracker.blockedStatus("wifi", false))
    }

    @Test
    fun aSwitchWhileBlockedIsAChangeAndStaysSuspendedUntilTheUnblock() {
        val tracker = DefaultNetworkTracker("wifi")
        assertEquals(suspended, tracker.blockedStatus("wifi", true))
        assertEquals(changed, tracker.available("other-wifi"))
        assertEquals(nothing, tracker.blockedStatus("other-wifi", true), "reported blocked on arrival: still suspended")
        assertEquals(resumed, tracker.blockedStatus("other-wifi", false))
    }

    @Test
    fun aSwitchReportedUnblockedEndsTheSuspension() {
        val tracker = DefaultNetworkTracker("wifi")
        tracker.blockedStatus("wifi", true)
        assertEquals(changed, tracker.available("other-wifi"))
        assertEquals(resumed, tracker.blockedStatus("other-wifi", false))
    }

    @Test
    fun losingTheNetworkWhileBlockedIsAChangeAndStaysSuspended() {
        val tracker = DefaultNetworkTracker("wifi")
        tracker.blockedStatus("wifi", true)
        assertEquals(changed, tracker.lost("wifi"))
        assertEquals(changed, tracker.available("other-wifi"))
        assertEquals(resumed, tracker.blockedStatus("other-wifi", false))
    }

    @Test
    fun aBlockedStatusForAnotherNetworkMeansTheDefaultChanged() {
        val tracker = DefaultNetworkTracker("wifi")
        assertEquals(changed + suspended, tracker.blockedStatus("hotspot", true), "reported before its onAvailable")
        assertEquals(nothing, tracker.available("hotspot"))
        assertEquals(resumed, tracker.blockedStatus("hotspot", false))
    }
}
