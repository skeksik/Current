package com.skeksik.current.ui.shell

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.skeksik.current.core.designsystem.CurrentIcons
import com.skeksik.current.core.designsystem.CurrentTokens.Size
import com.skeksik.current.core.designsystem.CurrentTokens.Spacing
import com.skeksik.current.core.designsystem.LocalFocusVisibility
import com.skeksik.current.core.designsystem.LocalPageTop
import com.skeksik.current.core.designsystem.SidebarItem
import com.skeksik.current.core.designsystem.SidebarItems
import com.skeksik.current.core.designsystem.SidebarScaffold
import com.skeksik.current.core.designsystem.SidebarTitle
import com.skeksik.current.core.designsystem.focusRing
import com.skeksik.current.core.designsystem.trackFocusVisibility
import com.skeksik.current.ui.navigation.Navigator
import com.skeksik.current.ui.navigation.ShellGraph
import com.skeksik.current.ui.navigation.ShellHistory
import com.skeksik.current.ui.resources.Res
import com.skeksik.current.ui.resources.app_name
import com.skeksik.current.ui.resources.nav_back
import org.jetbrains.compose.resources.stringResource
import kotlin.reflect.KClass

/**
 * The app on wide screens: the sidebar from [graph], and the current page beside it. Navigation keeps one history ([ShellHistory]): a
 * sidebar entry opens its feature's first page as a new step, and any Back goes one step back through it. Pages below a sidebar entry's
 * first page get a Back button at the top of the page ([LocalPageTop]); Alt+Left, Esc and a system or remote Back work on every page.
 *
 * A page shown again after the one above it left (any Back: the button, Alt+Left, Esc, a system or remote Back) gets focus on its first
 * element, or the sidebar entry when it has none, unless the page puts focus somewhere itself (on what opened the page that left).
 * Only after keyboard input, so a touch never opens a soft keyboard, and not while the sidebar holds focus (an entry that reopens the
 * start page).
 */
@Composable
fun Shell(graph: ShellGraph) {
    val controller = rememberNavController()
    // Back stack entries shown so far in this composition: one shown again is a return. Not saved, so a recreated activity restores none.
    val shownEntries = remember { mutableSetOf<String>() }
    // Entries rebuilt when the history is trimmed were shown before: going back to one is a return.
    val navigator = remember(controller) { ControllerNavigator(controller, graph.start, onRebuilt = shownEntries::addAll) }
    val entry by controller.currentBackStackEntryAsState()
    val destination = entry?.destination
    val owner = destination?.let { shown -> graph.owner { shown.hasRoute(it) } }
    val selectedEntry = remember { FocusRequester() }
    val focusVisibility = LocalFocusVisibility.current
    var sidebarHasFocus by remember { mutableStateOf(false) }

    SidebarScaffold(
        modifier = Modifier.trackFocusVisibility().onPreviewKeyEvent { event ->
            val back = event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft && event.isAltPressed && navigator.canGoBack
            if (back) navigator.back()
            back
        },
        sidebarModifier = Modifier.onFocusChanged { sidebarHasFocus = it.hasFocus },
        sidebar = {
            SidebarTitle(stringResource(Res.string.app_name))
            val features = graph.destinations.filter { it.sidebar != null }
            SidebarItems(features.size) { index, modifier ->
                val feature = features[index]
                val sidebar = checkNotNull(feature.sidebar)
                val selected = feature == owner
                SidebarItem(
                    stringResource(sidebar.label),
                    sidebar.icon,
                    selected = selected,
                    modifier = if (selected) modifier.focusRequester(selectedEntry) else modifier,
                ) {
                    // A new step on top of whatever is shown, even an inner page of the same feature (Add server): Back returns there.
                    navigator.open(sidebar.route)
                }
            }
        },
    ) {
        NavHost(controller, startDestination = graph.start, modifier = Modifier.fillMaxSize()) {
            graph.pages.forEach { page ->
                val atRoot = graph.sidebar.any { it.route::class == page.route }
                @Suppress("UNCHECKED_CAST")
                composable(page.route as KClass<Any>, typeMap = emptyMap()) { entry ->
                    val pageFocus = remember { FocusRequester() }
                    // Before the page's own effects, so a page that focuses something itself has the last word.
                    LaunchedEffect(Unit) {
                        val returning = !shownEntries.add(entry.id)
                        if (returning && focusVisibility.keyboard && !sidebarHasFocus && !pageFocus.requestFocus()) {
                            selectedEntry.requestFocus()
                        }
                    }
                    val back: (@Composable () -> Unit)? = if (atRoot) null else ({ BackButton(navigator::back) })
                    CompositionLocalProvider(LocalPageTop provides back) {
                        Box(Modifier.fillMaxSize().focusRequester(pageFocus).focusGroup()) { page.content(entry, navigator) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    // Shifted by the button's own padding, so the arrow lines up with the page title.
    TextButton(onClick, Modifier.offset(x = -Spacing.m).focusRing(MaterialTheme.shapes.extraLarge)) {
        Icon(CurrentIcons.Back, contentDescription = null, modifier = Modifier.size(Size.icon))
        Text(stringResource(Res.string.nav_back), Modifier.padding(start = Spacing.s))
    }
}

/**
 * [Navigator] over the shell's [controller], keeping its back stack to [ShellHistory.LIMIT] pages. Navigation cannot drop the oldest entry,
 * so the newest ones are rebuilt in a fresh back stack instead, from the routes they were opened with (the first page shown, which was not
 * opened here, from [start]). A rebuilt page starts afresh: its saved state (scroll, what opened the page above it) is gone.
 */
private class ControllerNavigator(
    private val controller: NavHostController,
    private val start: Any,
    private val onRebuilt: (entryIds: List<String>) -> Unit,
) : Navigator {
    private val routes = mutableMapOf<String, Any>()

    val canGoBack: Boolean get() = controller.previousBackStackEntry != null

    override fun open(route: Any) {
        // Single top: a held Enter, a double click or the page already shown opens it once, so it adds no step to make room for.
        if (controller.currentDestination?.hasRoute(route::class) != true) {
            ShellHistory.keptBeforeOpening(pages())?.let(::rebuild)
        }
        controller.navigate(route) { launchSingleTop = true }
        recordCurrent(route)
    }

    override fun replace(route: Any) {
        val current = controller.currentDestination?.id
        controller.navigate(route) { if (current != null) popUpTo(current) { inclusive = true } }
        recordCurrent(route)
    }

    override fun back() {
        if (canGoBack) controller.popBackStack()
    }

    private fun pages(): List<NavBackStackEntry> = controller.currentBackStack.value.filter { it.destination !is NavGraph }

    private fun rebuild(kept: List<NavBackStackEntry>) {
        val keptRoutes = kept.map { routes[it.id] ?: start }
        controller.navigate(keptRoutes.first()) { popUpTo(controller.graph.id) { inclusive = true } }
        recordCurrent(keptRoutes.first())
        keptRoutes.drop(1).forEach { route ->
            controller.navigate(route)
            recordCurrent(route)
        }
        onRebuilt(pages().map { it.id })
    }

    private fun recordCurrent(route: Any) {
        routes.keys.retainAll(pages().map { it.id }.toSet())
        controller.currentBackStackEntry?.let { routes[it.id] = route }
    }
}
