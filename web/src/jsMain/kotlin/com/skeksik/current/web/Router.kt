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
            // Only Jellyfin has a sign-in page yet (signInPath).
            if (parts[2] != ServiceType.jellyfin.value || parts[3].isBlank()) return null
            return SignIn(ServerRef(ServiceType.jellyfin, parts[3]))
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

/**
 * The page shown and how it came to be shown: [opener] names what opened the page above it (a `data-focus-id`), set only when Back or
 * Forward returns to an entry that recorded one, so the app puts focus back there. [visit] grows with every navigation.
 */
internal data class Shown(val route: Route, val opener: String?, val visit: Int)

private fun locationPath(): String = js("window.location.pathname")

/** The step stored in the current history entry, or -1 for an entry the app did not make (the first page load of the tab). */
private fun currentStep(): Int = js("(function (s) { return s && typeof s.step === 'number' ? s.step : -1; })(window.history.state)")

private fun currentOpener(): String? = js("(function (s) { return s && typeof s.opener === 'string' ? s.opener : null; })(history.state)")

@Suppress("UNUSED_PARAMETER")
private fun pushState(step: Int, path: String): Unit = js("window.history.pushState({ step: step }, '', path)")

/** Rewrites the current entry's state, and its URL unless [path] is null (an undefined URL keeps the query and fragment). */
@Suppress("UNUSED_PARAMETER")
private fun replaceState(step: Int, opener: String?, path: String?): Unit =
    js("window.history.replaceState({ step: step, opener: opener }, '', path === null ? undefined : path)")

private fun historyBack(): Unit = js("window.history.back()")

@Suppress("UNUSED_PARAMETER")
private fun onPopState(handler: () -> Unit): Unit = js("window.addEventListener('popstate', function () { handler(); })")

private fun scrollToTop(): Unit = js("window.scrollTo(0, 0)")

/**
 * Routing through the History API: the [shown] page follows the URL; Back, Forward and reloads work. Every page opened is a new history
 * entry (as on desktop, docs/architecture.md). Each entry records its step, so a Back from the first page opened in the tab shows that
 * page's parent rather than leaving the app, and what opened the next page, so focus goes back there on return.
 */
internal class BrowserRouter {
    private var step = currentStep()
    private var visit = 0

    private val _shown = MutableStateFlow(Shown(Route.parse(locationPath()), opener = null, visit))
    val shown: StateFlow<Shown> = _shown.asStateFlow()

    private val route: Route get() = _shown.value.route

    init {
        // A reload keeps the entry's state; a fresh tab gets step 0.
        if (step < 0) {
            step = 0
            replaceState(step, opener = null, path = null)
        }
        onPopState {
            step = currentStep().coerceAtLeast(0)
            show(Route.parse(locationPath()), currentOpener())
        }
    }

    /**
     * Opens [route] as a new history entry, recording [opener] in the entry left. The page already shown adds none and changes nothing
     * (a double click, or its sidebar entry, opens it once).
     */
    fun navigate(route: Route, opener: String?) {
        if (route.path == locationPath()) return
        replaceState(step, opener, path = null)
        step += 1
        pushState(step, route.path)
        show(route, opener = null)
        scrollToTop()
    }

    /** Shows [route] in place of the current entry, so Back skips the page it replaces (a finished step, such as adding a server). */
    fun replace(route: Route) {
        replaceState(step, opener = null, route.path)
        show(route, opener = null)
        scrollToTop()
    }

    fun back() {
        when (val next = backStep(step, route)) {
            BackStep.History -> historyBack()
            is BackStep.Replace -> replace(next.route)
            BackStep.None -> Unit
        }
    }

    private fun show(route: Route, opener: String?) {
        visit += 1
        _shown.value = Shown(route, opener, visit)
    }
}

/** A plain primary click is handled by the app; anything else (middle-click, Ctrl/Cmd/Shift/Alt) is left to the browser. */
internal fun isPlainClick(button: Int, ctrl: Boolean, meta: Boolean, shift: Boolean, alt: Boolean): Boolean =
    button == 0 && !ctrl && !meta && !shift && !alt
