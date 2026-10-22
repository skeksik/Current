package com.skeksik.current.web

import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServiceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A tab's history list, as the browser keeps it: entries with a path and the state the app stored, and the one shown. */
private class FakeHistory(firstPath: String) : BrowserHistory {
    class Entry(var path: String, var step: Int = -1, var opener: String? = null)

    val entries = mutableListOf(Entry(firstPath))
    var index = 0
    var scrolls = 0
    private val listeners = mutableListOf<() -> Unit>()

    private val current get() = entries[index]

    override val path get() = current.path
    override val step get() = current.step
    override val opener get() = current.opener

    override fun push(step: Int, path: String) {
        while (entries.lastIndex > index) entries.removeAt(entries.lastIndex)
        entries += Entry(path, step)
        index += 1
    }

    override fun replace(step: Int, opener: String?, path: String?) {
        current.step = step
        current.opener = opener
        if (path != null) current.path = path
    }

    /** The browser's Back: leaves the app when there is no entry before. */
    override fun back() = go(-1)

    fun forward() = go(1)

    private fun go(delta: Int) {
        val next = index + delta
        if (next !in entries.indices) return
        index = next
        // Only the newest router listens: a reload replaced the page that registered the others.
        listeners.lastOrNull()?.invoke()
    }

    override fun onPopState(handler: () -> Unit) {
        listeners += handler
    }

    override fun scrollToTop() {
        scrolls += 1
    }
}

class BrowserRouterTest {
    private val server = ServerRef(ServiceType.jellyfin, "0a1b2c")

    @Test
    fun theFirstPageOfATabIsTheUrlsAndGetsStepZero() {
        val history = FakeHistory("/settings/playback")
        val router = BrowserRouter(history)
        assertEquals(Route.PlaybackSupport, router.shown.value.route)
        assertEquals(0, history.step)
        assertEquals(1, history.entries.size)
    }

    @Test
    fun navigatingPushesAnEntryAndRecordsTheOpenerInTheOneLeft() {
        val history = FakeHistory("/settings")
        val router = BrowserRouter(history)
        router.navigate(Route.AddServer, opener = "add-server")
        assertEquals(Route.AddServer, router.shown.value.route)
        assertEquals(listOf("/settings", "/settings/servers/add"), history.entries.map { it.path })
        assertEquals("add-server", history.entries[0].opener)
        assertEquals(1, history.step)
        assertEquals(1, history.scrolls)
    }

    @Test
    fun backAfterNavigatingGoesThroughHistoryAndReturnsFocusToTheOpener() {
        val history = FakeHistory("/settings")
        val router = BrowserRouter(history)
        router.navigate(Route.AddServer, opener = "add-server")
        router.back()
        assertEquals(Route.Settings, router.shown.value.route)
        assertEquals("add-server", router.shown.value.opener)
        assertEquals(0, history.index)
    }

    @Test
    fun forwardShowsThePageAgain() {
        val history = FakeHistory("/settings")
        val router = BrowserRouter(history)
        router.navigate(Route.PlaybackSupport, opener = null)
        router.back()
        history.forward()
        assertEquals(Route.PlaybackSupport, router.shown.value.route)
        assertNull(router.shown.value.opener)
    }

    @Test
    fun backFromTheFirstPageOfTheTabShowsItsParentInPlaceAndStaysInTheApp() {
        val history = FakeHistory(Route.SignIn(server).path)
        val router = BrowserRouter(history)
        router.back()
        assertEquals(Route.Settings, router.shown.value.route)
        assertEquals(listOf("/settings"), history.entries.map { it.path }, "replaced, not pushed")
        router.back()
        assertEquals(Route.Settings, router.shown.value.route, "a sidebar page has nothing to go back to")
        assertEquals(1, history.entries.size)
    }

    @Test
    fun backFromAnAddressPageOpenedFirstWalksUpToSettings() {
        val history = FakeHistory(Route.AddServerAddress(server).path)
        val router = BrowserRouter(history)
        router.back()
        assertEquals(Route.ServerAddresses(server), router.shown.value.route)
        router.back()
        assertEquals(Route.Settings, router.shown.value.route)
        assertEquals(1, history.entries.size)
    }

    @Test
    fun aReloadKeepsTheStepSoBackStillGoesThroughHistory() {
        val history = FakeHistory("/settings")
        BrowserRouter(history).navigate(Route.AddServer, opener = "add-server")
        // The reload: a new page load on the same entry, with its state.
        val reloaded = BrowserRouter(history)
        assertEquals(Route.AddServer, reloaded.shown.value.route)
        assertEquals(1, history.step)
        reloaded.back()
        assertEquals(Route.Settings, reloaded.shown.value.route)
        assertEquals(0, history.index)
        assertEquals(2, history.entries.size, "the entry ahead stays for Forward")
    }

    @Test
    fun anUnknownPathIsNotFoundAndBackGoesHome() {
        val history = FakeHistory("/library/42")
        val router = BrowserRouter(history)
        assertEquals(Route.NotFound("/library/42"), router.shown.value.route)
        router.back()
        assertEquals(Route.Home, router.shown.value.route)
        assertEquals("/", history.path)
    }

    @Test
    fun thePageAlreadyShownIsNotOpenedAgainEvenWithATrailingSlash() {
        val history = FakeHistory("/settings/")
        val router = BrowserRouter(history)
        val shown = router.shown.value
        router.navigate(Route.Settings, opener = "nav-settings")
        assertEquals(1, history.entries.size)
        assertEquals(shown, router.shown.value)
        assertNull(history.opener)
    }

    @Test
    fun replaceSwapsTheEntrySoBackSkipsIt() {
        val history = FakeHistory("/settings")
        val router = BrowserRouter(history)
        router.navigate(Route.AddServer, opener = "add-server")
        router.replace(Route.SignIn(server))
        assertEquals(listOf("/settings", Route.SignIn(server).path), history.entries.map { it.path })
        router.back()
        assertEquals(Route.Settings, router.shown.value.route)
    }

    @Test
    fun everyNavigationIsANewVisitEvenToTheSamePage() {
        val history = FakeHistory("/settings")
        val router = BrowserRouter(history)
        val first = router.shown.value.visit
        router.navigate(Route.AddServer, opener = null)
        router.back()
        router.navigate(Route.AddServer, opener = null)
        assertEquals(first + 3, router.shown.value.visit)
    }
}
