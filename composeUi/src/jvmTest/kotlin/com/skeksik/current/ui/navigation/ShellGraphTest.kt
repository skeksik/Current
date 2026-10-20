package com.skeksik.current.ui.navigation

import com.skeksik.current.core.designsystem.CurrentIcons
import com.skeksik.current.ui.home.HomeDestination
import com.skeksik.current.ui.home.HomeRoute
import com.skeksik.current.ui.home.homeUiModule
import com.skeksik.current.ui.resources.Res
import com.skeksik.current.ui.resources.nav_home
import com.skeksik.current.ui.settings.SettingsDestination
import com.skeksik.current.ui.settings.settingsUiModule
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

@Serializable
private data object OtherRoute

private class Fake(override val pages: List<ShellPage>, override val sidebar: SidebarEntry?) : ShellDestination

private fun page(route: KClass<*>) = ShellPage(route) { _, _ -> }

class ShellGraphTest {
    @Test
    fun theUiModulesContributeEveryDestination() {
        val koin = koinApplication { modules(settingsUiModule, homeUiModule) }.koin
        assertEquals(setOf(HomeDestination, SettingsDestination), koin.getAll<ShellDestination>().toSet())
    }

    @Test
    fun theSidebarFollowsOrderNotRegistrationAndTheAppStartsAtHome() {
        val graph = ShellGraph(listOf(SettingsDestination, HomeDestination))
        assertEquals(listOf<Any>(HomeRoute, SettingsRoute), graph.sidebar.map { it.route })
        assertEquals(HomeRoute, graph.start)
    }

    @Test
    fun aFeatureWithoutSidebarEntryAddsPagesButNoEntry() {
        val pagesOnly = Fake(listOf(page(OtherRoute::class)), sidebar = null)
        val graph = ShellGraph(listOf(HomeDestination, pagesOnly))
        assertEquals(listOf<Any>(HomeRoute), graph.sidebar.map { it.route })
        assertTrue(graph.pages.any { it.route == OtherRoute::class })
    }

    @Test
    fun aGraphWithoutSidebarEntryIsRejected() {
        assertFailsWith<IllegalArgumentException> { ShellGraph(listOf(Fake(listOf(page(OtherRoute::class)), sidebar = null))) }
    }

    @Test
    fun aPageOwnedByTwoFeaturesIsRejected() {
        val copy = Fake(listOf(page(SettingsRoute::class)), sidebar = null)
        assertFailsWith<IllegalArgumentException> { ShellGraph(listOf(HomeDestination, SettingsDestination, copy)) }
    }

    @Test
    fun aSidebarEntryMustLeadToItsOwnFeature() {
        val stray = Fake(listOf(page(OtherRoute::class)), SidebarEntry(HomeRoute, Res.string.nav_home, CurrentIcons.Home, order = 5))
        assertFailsWith<IllegalArgumentException> { ShellGraph(listOf(SettingsDestination, stray)) }
    }

    @Test
    fun twoEntriesWithTheSameOrderAreRejected() {
        val twin = Fake(listOf(page(OtherRoute::class)), SidebarEntry(OtherRoute, Res.string.nav_home, CurrentIcons.Home, order = 0))
        assertFailsWith<IllegalArgumentException> { ShellGraph(listOf(HomeDestination, twin)) }
    }

    @Test
    fun theSidebarHighlightsTheFeatureOwningTheShownPage() {
        val graph = ShellGraph(listOf(HomeDestination, SettingsDestination))
        assertSame(SettingsDestination, graph.owner { it == AddServerRoute::class })
        assertSame(HomeDestination, graph.owner { it == HomeRoute::class })
    }
}
