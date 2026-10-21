package com.skeksik.current.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavBackStackEntry
import org.jetbrains.compose.resources.StringResource
import kotlin.reflect.KClass

/**
 * What one feature adds to the shell's navigation (ADR 0014): its pages and, if it has one, its sidebar entry. Each feature's Compose
 * code binds its destination in its own Koin module (`bind ShellDestination::class`); the shell collects every binding, so the shell's
 * list of modules is the only place that names them all.
 */
interface ShellDestination {
    /** Every page this feature shows. Routes are `@Serializable` types; [sidebar]'s route must be one of them. */
    val pages: List<ShellPage>

    /** Null for a feature reached only from other pages. */
    val sidebar: SidebarEntry?
}

/** One page: its [route] type and how to draw it. Read the route's arguments with `entry.toRoute<T>()`. */
class ShellPage(
    val route: KClass<*>,
    val content: @Composable (entry: NavBackStackEntry, navigator: Navigator) -> Unit,
)

/** A feature's entry in the sidebar. Entries are shown by ascending [order]; the lowest is where the app starts. */
class SidebarEntry(
    /** The route opened by the entry, an instance of one of the feature's page routes. */
    val route: Any,
    val label: StringResource,
    val icon: ImageVector,
    val order: Int,
)

/** How a page moves to another page. */
interface Navigator {
    /** Opens [route] on top of the current page. */
    fun open(route: Any)

    /** Opens [route] in place of the current page, so Back skips it (a finished step, such as adding a server). */
    fun replace(route: Any)

    /** Goes back to the previous page. */
    fun back()
}

/** The shell's destinations, checked: every route once, sidebar entries in order. */
class ShellGraph(destinations: Collection<ShellDestination>) {
    val destinations: List<ShellDestination> = destinations.sortedBy { it.sidebar?.order ?: Int.MAX_VALUE }

    init {
        val routes = this.destinations.flatMap { destination -> destination.pages.map { it.route } }
        val repeated = routes.groupBy { it }.filterValues { it.size > 1 }.keys
        require(repeated.isEmpty()) { "Routes registered more than once: ${repeated.joinToString { it.simpleName.toString() }}" }
        val orders = this.destinations.mapNotNull { it.sidebar?.order }
        require(orders.size == orders.toSet().size) { "Two sidebar entries share an order: $orders" }
        this.destinations.forEach { destination ->
            val sidebar = destination.sidebar ?: return@forEach
            require(destination.pages.any { it.route.isInstance(sidebar.route) }) { "A sidebar entry leads to a page of another feature" }
        }
        require(orders.isNotEmpty()) { "The shell needs at least one sidebar entry to start from" }
    }

    /** The sidebar, top to bottom. */
    val sidebar: List<SidebarEntry> = this.destinations.mapNotNull { it.sidebar }

    /** Where the app starts: the first sidebar entry. */
    val start: Any = sidebar.first().route

    val pages: List<ShellPage> = this.destinations.flatMap { it.pages }

    /** The destination owning the page whose route [isShown], to highlight its sidebar entry. */
    fun owner(isShown: (KClass<*>) -> Boolean): ShellDestination? =
        destinations.firstOrNull { destination -> destination.pages.any { isShown(it.route) } }
}
