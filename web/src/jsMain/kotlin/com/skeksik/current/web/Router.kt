package com.skeksik.current.web

import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServiceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App URLs. Every page has its own path, so links work with middle-click, new tabs, copy link and reloads. */
internal sealed interface Route {
    val path: String

    /** Where Back goes when this page is the first one opened in the tab; null for the sidebar's pages, which have no Back button. */
    val parent: Route?

    data object Home : Route {
        override val path = "/"
        override val parent: Route? = null
    }

    data object Settings : Route {
        override val path = "/settings"
        override val parent: Route? = null
    }

    data object AddServer : Route {
        override val path = "/settings/servers/add"
        override val parent: Route = Settings
    }

    /** Sign in to a saved server. The path holds only the server's ref, so a reload finds the server's name among the saved ones. */
    data class SignIn(val server: ServerRef) : Route {
        override val path = "/settings/servers/${server.service.value}/${server.key}/sign-in"
        override val parent: Route = Settings
    }

    data object PlaybackSupport : Route {
        override val path = "/settings/playback"
        override val parent: Route = Settings
    }

    data class NotFound(override val path: String) : Route {
        override val parent: Route = Home
    }

    companion object {
        fun parse(path: String): Route {
            val clean = path.substringBefore('?').substringBefore('#').trimEnd('/')
            return when (clean) {
                "" -> Home
                Settings.path -> Settings
                AddServer.path -> AddServer
                PlaybackSupport.path -> PlaybackSupport
                else -> signIn(clean) ?: NotFound(path)
            }
        }

        private fun signIn(path: String): Route? {
            val parts = path.removePrefix("/").split('/')
            if (parts.size != 5 || parts[0] != "settings" || parts[1] != "servers" || parts[4] != "sign-in") return null
            if (parts[2].isBlank() || parts[3].isBlank()) return null
            return SignIn(ServerRef(ServiceType(parts[2]), parts[3]))
        }
    }
}

/** What a Back inside the app does. */
internal sealed interface BackStep {
    /** Go back one history entry: the page before was opened in this tab. */
    data object History : BackStep

    /** The page was the first one opened in this tab (a link, a bookmark, a reload): show its parent in its place, never leave the app. */
    data class Replace(val route: Route) : BackStep

    /** A sidebar page opened first: nothing to go back to inside the app. */
    data object None : BackStep
}

/** [step] is the history entry's index among the entries this tab opened in the app, 0 for the first. */
internal fun backStep(step: Int, route: Route): BackStep = when {
    step > 0 -> BackStep.History
    else -> route.parent?.let { BackStep.Replace(it) } ?: BackStep.None
}

private fun locationPath(): String = js("window.location.pathname")

/** The step stored in the current history entry, or -1 for an entry the app did not make (the first page load of the tab). */
private fun currentStep(): Int = js("(function (s) { return s && typeof s.step === 'number' ? s.step : -1; })(window.history.state)")

@Suppress("UNUSED_PARAMETER")
private fun pushState(step: Int, path: String): Unit = js("window.history.pushState({ step: step }, '', path)")

@Suppress("UNUSED_PARAMETER")
private fun replaceState(step: Int, path: String): Unit = js("window.history.replaceState({ step: step }, '', path)")

private fun historyBack(): Unit = js("window.history.back()")

@Suppress("UNUSED_PARAMETER")
private fun onPopState(handler: () -> Unit): Unit = js("window.addEventListener('popstate', function () { handler(); })")

private fun scrollToTop(): Unit = js("window.scrollTo(0, 0)")

/**
 * Routing through the History API: the current [route] follows the URL; Back, Forward and reloads work. Every page opened is a new
 * history entry (as on desktop, docs/architecture.md), and each entry records its [step], so a Back from the first page opened in the
 * tab shows that page's parent rather than leaving the app.
 */
internal class BrowserRouter {
    private val _route = MutableStateFlow(Route.parse(locationPath()))
    val route: StateFlow<Route> = _route.asStateFlow()

    private var step = currentStep()

    init {
        // A reload keeps the entry's state; a fresh tab gets step 0.
        if (step < 0) {
            step = 0
            replaceState(step, locationPath())
        }
        onPopState {
            step = currentStep().coerceAtLeast(0)
            _route.value = Route.parse(locationPath())
        }
    }

    /** Opens [route] as a new history entry; the page already shown adds none (a double click opens it once). */
    fun navigate(route: Route) {
        if (route.path != locationPath()) {
            step += 1
            pushState(step, route.path)
        }
        _route.value = route
        scrollToTop()
    }

    /** Shows [route] in place of the current entry, so Back skips the page it replaces (a finished step, such as adding a server). */
    fun replace(route: Route) {
        replaceState(step, route.path)
        _route.value = route
        scrollToTop()
    }

    fun back() {
        when (val next = backStep(step, _route.value)) {
            BackStep.History -> historyBack()
            is BackStep.Replace -> replace(next.route)
            BackStep.None -> Unit
        }
    }
}

/** A plain primary click is handled by the app; anything else (middle-click, Ctrl/Cmd/Shift/Alt) is left to the browser. */
internal fun isPlainClick(button: Int, ctrl: Boolean, meta: Boolean, shift: Boolean, alt: Boolean): Boolean =
    button == 0 && !ctrl && !meta && !shift && !alt
