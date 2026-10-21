package com.skeksik.current.ui.navigation

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestinationBuilder
import androidx.navigation.NavHostController
import androidx.navigation.createGraph
import androidx.savedstate.SavedState
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Serializable
private data object Start

@Serializable
private data class Page(val n: Int)

@Serializable
private data class Detail(val n: Int, val name: String)

private val routes = listOf(Start::class, Page::class, Detail::class)

/** Pages without any UI: entries are pushed and popped as a composable destination's would be. */
private class Pages : androidx.navigation.Navigator<NavDestination>(NAME) {
    override fun createDestination() = NavDestination(NAME)

    companion object {
        const val NAME = "pages"
    }
}

/** A resumed host, as the shell's window or activity is; without one the entries never get to save their state. */
private object Host : LifecycleOwner {
    override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
}

/** A controller like the shell's, restored from [saved] first when given (what an activity recreation or process death does). */
private fun controller(saved: SavedState? = null): NavController = NavHostController().apply {
    setLifecycleOwner(Host)
    setViewModelStore(ViewModelStore())
    val pages = Pages()
    navigatorProvider.addNavigator(Pages.NAME, pages)
    if (saved != null) restoreState(saved)
    graph = createGraph(startDestination = Start::class) {
        destination(NavDestinationBuilder(pages, Start::class, emptyMap()))
        destination(NavDestinationBuilder(pages, Page::class, emptyMap()))
        destination(NavDestinationBuilder(pages, Detail::class, emptyMap()))
    }
}

/** Page 1, Detail 1, Page 2, Detail 2, ...: every step a different route type, so none is single top. */
private fun step(i: Int): Any = if (i % 2 == 0) Page(i / 2 + 1) else Detail(i / 2 + 1, "detail ${i / 2 + 1}")

class ShellNavigatorTest {
    @Test
    fun theHistoryKeepsTheNewestPagesWithTheirRoutes() {
        val navigator = ShellNavigator(controller(), routes)
        val opened = (0 until 60).map(::step)
        opened.forEach(navigator::open)

        assertEquals(opened.takeLast(ShellHistory.LIMIT), navigator.history())
    }

    @Test
    fun aRestoredBackStackKeepsItsRoutesWhenTheHistoryIsTrimmed() {
        val before = controller()
        val opened = (0 until 45).map(::step)
        ShellNavigator(before, routes).apply { opened.forEach(::open) }
        val saved = before.saveState()

        val restored = controller(saved)
        val navigator = ShellNavigator(restored, routes)
        assertEquals(listOf<Any>(Start) + opened, navigator.history())

        // Past the limit, the kept pages are rebuilt from the restored entries: none turns into the start page.
        val more = (45 until 60).map(::step)
        more.forEach(navigator::open)
        val all = listOf<Any>(Start) + opened + more
        assertEquals(all.takeLast(ShellHistory.LIMIT), navigator.history())
        assertFalse(Start in navigator.history())
    }

    @Test
    fun theRebuiltEntriesAreReported() {
        var rebuilt = emptyList<String>()
        val controller = controller()
        val navigator = ShellNavigator(controller, routes) { rebuilt = it }
        (0 until ShellHistory.LIMIT).map(::step).forEach(navigator::open)

        assertEquals(ShellHistory.LIMIT - 1, rebuilt.size)
        assertTrue(rebuilt.all { id -> controller.currentBackStack.value.any { it.id == id } })
    }

    @Test
    fun openingThePageShownAddsNoStep() {
        val navigator = ShellNavigator(controller(), routes)
        navigator.open(Page(1))
        navigator.open(Page(1))

        assertEquals(listOf<Any>(Start, Page(1)), navigator.history())
    }

    @Test
    fun replaceTakesThePlaceOfThePageShownAndBackSkipsIt() {
        val navigator = ShellNavigator(controller(), routes)
        navigator.open(Page(1))
        navigator.open(Detail(1, "added"))
        navigator.replace(Page(2))
        assertEquals(listOf<Any>(Start, Page(1), Page(2)), navigator.history())

        navigator.back()
        assertEquals(listOf<Any>(Start, Page(1)), navigator.history())
    }

    @Test
    fun backStopsAtTheFirstPage() {
        val navigator = ShellNavigator(controller(), routes)
        assertFalse(navigator.canGoBack)
        navigator.back()
        assertEquals(listOf<Any>(Start), navigator.history())
    }
}
