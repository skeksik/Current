package com.skeksik.current.ui.navigation

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph
import androidx.navigation.toRoute
import kotlin.reflect.KClass

/**
 * [Navigator] over the shell's [controller], keeping its back stack to [ShellHistory.LIMIT] pages. Navigation cannot drop the oldest entry,
 * so the newest ones are rebuilt in a fresh back stack instead, from the routes they show. A rebuilt page starts afresh: its saved state
 * (scroll, what opened the page above it) is gone.
 *
 * Each entry's route is read back from the entry itself ([routes] are the page route types), not kept aside: the controller saves and
 * restores its back stack across an activity recreation or process death, and a side map would come back empty.
 */
internal class ShellNavigator(
    private val controller: NavController,
    private val routes: List<KClass<*>>,
    private val onRebuilt: (entryIds: List<String>) -> Unit = {},
) : Navigator {
    val canGoBack: Boolean get() = controller.previousBackStackEntry != null

    override fun open(route: Any) {
        // Single top: a held Enter, a double click or the page already shown opens it once, so it adds no step to make room for.
        if (controller.currentDestination?.hasRoute(route::class) != true) {
            ShellHistory.keptBeforeOpening(pages())?.let(::rebuild)
        }
        controller.navigate(route) { launchSingleTop = true }
    }

    override fun replace(route: Any) {
        val current = controller.currentDestination?.id
        controller.navigate(route) { if (current != null) popUpTo(current) { inclusive = true } }
    }

    override fun back() {
        if (canGoBack) controller.popBackStack()
    }

    /** The route each page in the back stack shows, oldest first. */
    fun history(): List<Any> = pages().map(::routeOf)

    private fun pages(): List<NavBackStackEntry> = controller.currentBackStack.value.filter { it.destination !is NavGraph }

    private fun rebuild(kept: List<NavBackStackEntry>) {
        val keptRoutes = kept.map(::routeOf)
        controller.navigate(keptRoutes.first()) { popUpTo(controller.graph.id) { inclusive = true } }
        keptRoutes.drop(1).forEach { controller.navigate(it) }
        onRebuilt(pages().map { it.id })
    }

    private fun routeOf(entry: NavBackStackEntry): Any {
        val type = checkNotNull(routes.firstOrNull { entry.destination.hasRoute(it) }) { "No page registered for ${entry.destination}" }
        return entry.toRoute<Any>(type)
    }
}
