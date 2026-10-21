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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
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
import com.skeksik.current.ui.navigation.ShellGraph
import com.skeksik.current.ui.navigation.ShellHistory
import com.skeksik.current.ui.navigation.ShellNavigator
import com.skeksik.current.ui.resources.Res
import com.skeksik.current.ui.resources.app_name
import com.skeksik.current.ui.resources.nav_back
import org.jetbrains.compose.resources.stringResource
import kotlin.reflect.KClass

/**
 * The app on wide screens: the sidebar from [graph], and the current page beside it. Navigation keeps one history ([ShellHistory]): a
 * sidebar entry opens its feature's first page as a new step, and any Back goes one step back through it. Pages below a sidebar entry's
 * first page get a Back button at the top of the page ([LocalPageTop]). On every page, even with nothing focused, Back is Alt+Left, the
 * mouse back button on desktop and a system or remote Back ([back]); an open dialog or menu takes it first and closes. Esc never goes
 * back: it closes dialogs, menus and other transient UI only.
 *
 * A page shown again after the one above it left (by any Back) gets focus on its first element, or the sidebar entry when it has none,
 * unless the page puts focus somewhere itself (on what opened the page that left). Only after keyboard input, so a touch never opens a
 * soft keyboard, and not while the sidebar holds focus (an entry that reopens the start page). Pages follow the same rule when they
 * place focus by themselves, with two exceptions: focus that follows the user's own action on the page (Check focuses Confirm, a failed
 * sign-in the password), and a form whose first field is the point of the page (Add server, Sign in), which focuses it when it opens,
 * so a tap that opens it on Android also opens the soft keyboard.
 */
@Composable
fun Shell(graph: ShellGraph, back: BackInput) {
    val controller = rememberNavController()
    // Back stack entries shown so far: one shown again is a return. Saved with the back stack, whose entries keep their ids when restored.
    val shownEntries = rememberSaveable(saver = shownEntriesSaver) { mutableSetOf<String>() }
    // Entries rebuilt when the history is trimmed were shown before: going back to one is a return.
    val navigator = remember(controller) {
        ShellNavigator(controller, graph.pages.map { it.route }, onRebuilt = shownEntries::addAll)
    }
    val entry by controller.currentBackStackEntryAsState()
    val destination = entry?.destination
    val owner = destination?.let { shown -> graph.owner { shown.hasRoute(it) } }
    val selectedEntry = remember { FocusRequester() }
    val focusVisibility = LocalFocusVisibility.current
    var sidebarHasFocus by remember { mutableStateOf(false) }

    SidebarScaffold(
        modifier = Modifier.trackFocusVisibility(),
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
        Box(Modifier.fillMaxSize()) {
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
                        val top: (@Composable () -> Unit)? = if (atRoot) null else ({ BackButton(navigator::back) })
                        CompositionLocalProvider(LocalPageTop provides top) {
                            Box(Modifier.fillMaxSize().focusRequester(pageFocus).focusGroup()) { page.content(entry, navigator) }
                        }
                    }
                }
            }
            // Over the page, at the bottom, where it covers the least.
            RoamingNoticeHost(Modifier.align(Alignment.BottomCenter).padding(Spacing.l))
        }
    }
    // After NavHost, whose own back handler is then the older one (ShellBackHandler).
    val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
    if (dispatcher != null) {
        DisposableEffect(dispatcher, back) {
            back.attach(dispatcher)
            val handler = if (back.windowBackIsEscape) ShellBackHandler(back, navigator::back).also(dispatcher::addHandler) else null
            onDispose {
                handler?.remove()
                back.detach(dispatcher)
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

private val shownEntriesSaver = listSaver<MutableSet<String>, String>(save = { it.toList() }, restore = { it.toMutableSet() })
