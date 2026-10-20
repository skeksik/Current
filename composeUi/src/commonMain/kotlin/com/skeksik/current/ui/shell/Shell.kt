package com.skeksik.current.ui.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import com.skeksik.current.core.designsystem.SidebarItem
import com.skeksik.current.core.designsystem.SidebarItems
import com.skeksik.current.core.designsystem.SidebarScaffold
import com.skeksik.current.core.designsystem.SidebarTitle
import com.skeksik.current.core.designsystem.focusRing
import com.skeksik.current.ui.navigation.Navigator
import com.skeksik.current.ui.navigation.ShellGraph
import com.skeksik.current.ui.resources.Res
import com.skeksik.current.ui.resources.app_name
import com.skeksik.current.ui.resources.nav_back
import org.jetbrains.compose.resources.stringResource
import kotlin.reflect.KClass

/**
 * The app on wide screens: the sidebar from [graph], and the current page beside it. Pages below a sidebar entry's first page get a Back
 * button; Alt+Left goes back too.
 */
@Composable
fun Shell(graph: ShellGraph) {
    val controller = rememberNavController()
    val navigator = remember(controller) { ControllerNavigator(controller) }
    val entry by controller.currentBackStackEntryAsState()
    val destination = entry?.destination
    val owner = destination?.let { shown -> graph.owner { shown.hasRoute(it) } }
    val atSidebarRoot = destination == null || graph.sidebar.any { destination.hasRoute(it.route::class) }

    SidebarScaffold(
        modifier = Modifier.onPreviewKeyEvent { event ->
            val back = event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft && event.isAltPressed && !atSidebarRoot
            if (back) navigator.back()
            back
        },
        sidebar = {
            SidebarTitle(stringResource(Res.string.app_name))
            val features = graph.destinations.filter { it.sidebar != null }
            SidebarItems(features.size) { index, modifier ->
                val feature = features[index]
                val sidebar = checkNotNull(feature.sidebar)
                SidebarItem(stringResource(sidebar.label), sidebar.icon, selected = feature == owner, modifier = modifier) {
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
        Column(Modifier.fillMaxSize()) {
            if (!atSidebarRoot) {
                TextButton(
                    navigator::back,
                    Modifier.padding(start = Spacing.xl, top = Spacing.l).focusRing(MaterialTheme.shapes.extraLarge),
                ) {
                    Icon(CurrentIcons.Back, contentDescription = null, modifier = Modifier.size(Size.icon))
                    Text(stringResource(Res.string.nav_back), Modifier.padding(start = Spacing.s))
                }
            }
            NavHost(controller, startDestination = graph.start, modifier = Modifier.weight(1f)) {
                graph.pages.forEach { page ->
                    @Suppress("UNCHECKED_CAST")
                    composable(page.route as KClass<Any>, typeMap = emptyMap()) { entry -> page.content(entry, navigator) }
                }
            }
        }
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
