package com.skeksik.current.web

import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServiceType
import kotlin.test.Test
import kotlin.test.assertEquals

class RouteTest {
    private val server = ServerRef(ServiceType.jellyfin, "0a1b2c")

    @Test
    fun everyPageParsesFromItsOwnPath() {
        val routes = listOf(Route.Home, Route.Settings, Route.AddServer, Route.SignIn(server), Route.PlaybackSupport)
        routes.forEach { assertEquals(it, Route.parse(it.path)) }
    }

    @Test
    fun aTrailingSlashQueryOrFragmentIsIgnored() {
        assertEquals(Route.Settings, Route.parse("/settings/"))
        assertEquals(Route.AddServer, Route.parse("/settings/servers/add?from=home"))
        assertEquals(Route.SignIn(server), Route.parse("/settings/servers/jellyfin/0a1b2c/sign-in#top"))
        assertEquals(Route.Home, Route.parse(""))
    }

    @Test
    fun anythingElseIsNotFound() {
        listOf("/settings/servers", "/settings/servers/jellyfin//sign-in", "/settings/servers/jellyfin/0a1b2c", "/library")
            .forEach { assertEquals(Route.NotFound(it), Route.parse(it)) }
    }

    @Test
    fun onlyJellyfinHasASignInPage() {
        val path = "/settings/servers/stash/0a1b2c/sign-in"
        assertEquals(Route.NotFound(path), Route.parse(path))
    }

    @Test
    fun theSidebarPagesHaveNoParentAndTheOthersReturnToTheirs() {
        assertEquals(null, Route.Home.parent)
        assertEquals(null, Route.Settings.parent)
        assertEquals(Route.Settings, Route.AddServer.parent)
        assertEquals(Route.Settings, Route.SignIn(server).parent)
        assertEquals(Route.Settings, Route.PlaybackSupport.parent)
        assertEquals(Route.Home, Route.NotFound("/x").parent)
    }

    @Test
    fun backGoesThroughTheHistoryWhenThisTabOpenedAPageBefore() {
        assertEquals(BackStep.History, backStep(step = 1, Route.AddServer))
        assertEquals(BackStep.History, backStep(step = 3, Route.Home))
    }

    @Test
    fun backFromTheFirstPageOfTheTabShowsItsParentInsteadOfLeaving() {
        assertEquals(BackStep.Replace(Route.Settings), backStep(step = 0, Route.SignIn(server)))
        assertEquals(BackStep.Replace(Route.Home), backStep(step = 0, Route.NotFound("/x")))
        assertEquals(BackStep.None, backStep(step = 0, Route.Home))
        assertEquals(BackStep.None, backStep(step = 0, Route.Settings))
    }

    @Test
    fun onlyAPlainPrimaryClickIsTheApps() {
        assertEquals(true, isPlainClick(0, ctrl = false, meta = false, shift = false, alt = false))
        assertEquals(false, isPlainClick(1, ctrl = false, meta = false, shift = false, alt = false))
        assertEquals(false, isPlainClick(0, ctrl = true, meta = false, shift = false, alt = false))
        assertEquals(false, isPlainClick(0, ctrl = false, meta = true, shift = false, alt = false))
    }
}
