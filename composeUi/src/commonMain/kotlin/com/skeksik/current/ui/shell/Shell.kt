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
import androidx.navigation.NavDestination.Companion.hasRoute
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
import com.skeksik.current.ui.resources.Res
import com.skeksik.current.ui.resources.app_name
import com.skeksik.current.ui.resources.nav_back
import org.jetbrains.compose.resources.stringResource
import kotlin.reflect.KClass

/**
 * The app on wide screens: the sidebar from [graph], and the current page beside it. Pages below a sidebar entry's first page get a Back
 * button at the top of the page ([LocalPageTop]); Alt+Left goes back too.
 *
 * A page shown again after the one above it left (any Back: the button, Alt+Left, Esc, a system or remote Back) gets focus on its first
 * element, or the sidebar entry when it has none, unless the page puts focus somewhere itself (on what opened the page that left).
 * Only after keyboard input, so a touch never opens a soft keyboard, and not while the sidebar holds focus (an entry that reopens the
 * start page).
 */
@Composable
fun Shell(graph: ShellGraph) {
    val controller = rememberNavController()
    val navigator = remember(controller) { ControllerNavigator(controller) }
    val entry by controller.currentBackStackEntryAsState()
    val destination = entry?.destination
    val owner = destination?.let { shown -> graph.owner { shown.hasRoute(it) } }
    val atSidebarRoot = destination == null || graph.sidebar.any { destination.hasRoute(it.route::class) }
    val selectedEntry = remember { FocusRequester() }
    val focusVisibility = LocalFocusVisibility.current
    var sidebarHasFocus by remember { mutableStateOf(false) }
    // Back stack entries shown so far in this composition: one shown again is a return. Not saved, so a recreated activity restores none.
    val shownEntries = remember { mutableSetOf<String>() }

    SidebarScaffold(
        modifier = Modifier.trackFocusVisibility().onPreviewKeyEvent { event ->
            val back = event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft && event.isAltPressed && !atSidebarRoot
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
                    // Every entry opens its feature's first page on top of the start page, even the entry already shown: an inner
                    // page (Add server) is left, not kept for later.
                    controller.navigate(sidebar.route) {
                        popUpTo(graph.start)
                        launchSingleTop = true
                    }
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

private class ControllerNavigator(private val controller: NavHostController) : Navigator {
    // Single top: a held Enter or a double click opens a page once.
    override fun open(route: Any) = controller.navigate(route) { launchSingleTop = true }

    override fun replace(route: Any) {
        val current = controller.currentDestination?.id
        controller.navigate(route) { if (current != null) popUpTo(current) { inclusive = true } }
    }

    override fun back() {
        if (controller.previousBackStackEntry != null) controller.popBackStack()
    }
}
