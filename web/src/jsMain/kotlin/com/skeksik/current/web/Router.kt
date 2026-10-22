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

    /** A saved server's addresses (ADR 0019). The path names the server by its ref only, never an address. */
    data class ServerAddresses(val server: ServerRef) : Route {
        override val path = "/settings/servers/${server.service.value}/${server.key}/addresses"
        override val parent: Route = Settings
    }

    /** Add an address to a saved server: Add server, accepting only the same server. */
    data class AddServerAddress(val server: ServerRef) : Route {
        override val path = "${ServerAddresses(server).path}/add"
        override val parent: Route = ServerAddresses(server)
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
                else -> serverPage(clean) ?: NotFound(path)
            }
        }

        /** `/settings/servers/<service>/<key>/` and a page of that server. */
        private fun serverPage(path: String): Route? {
            val parts = path.removePrefix("/").split('/')
            if (parts.size !in 5..6 || parts[0] != "settings" || parts[1] != "servers" || parts[3].isBlank()) return null
            val service = ServiceType.known.firstOrNull { it.value == parts[2] } ?: return null
            val server = ServerRef(service, parts[3])
            return when (parts.drop(4)) {
                // Only Jellyfin has a sign-in page yet (signInPath).
                listOf("sign-in") -> SignIn(server).takeIf { service == ServiceType.jellyfin }
                listOf("addresses") -> ServerAddresses(server)
                listOf("addresses", "add") -> AddServerAddress(server)
                else -> null
            }
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

/** The History API, behind an interface so [BrowserRouter] can be tested without a browser. */
internal interface BrowserHistory {
    /** `location.pathname`. */
    val path: String

    /** The step stored in the current entry, or -1 for an entry the app did not make (the first page load of the tab). */
    val step: Int

    /** The opener stored in the current entry, if any. */
    val opener: String?

    fun push(step: Int, path: String)

    /** Rewrites the current entry's state, and its URL unless [path] is null (which keeps the query and fragment). */
    fun replace(step: Int, opener: String?, path: String?)

    fun back()

    /** Calls [handler] after Back, Forward or a jump in the browser's history list. */
    fun onPopState(handler: () -> Unit)

    fun scrollToTop()
}

/** The browser's `window.history` and `location`. */
internal object WindowHistory : BrowserHistory {
    override val path: String get() = locationPath()

    override val step: Int get() = currentStep()

    override val opener: String? get() = currentOpener()

    override fun push(step: Int, path: String) = pushState(step, path)

    override fun replace(step: Int, opener: String?, path: String?) = replaceState(step, opener, path)

    override fun back() = historyBack()

    override fun onPopState(handler: () -> Unit) = addPopStateListener(handler)

    override fun scrollToTop() = windowScrollToTop()
}

// js() calls must be the whole body of a top-level function.

private fun locationPath(): String = js("window.location.pathname")

private fun currentStep(): Int = js("(function (s) { return s && typeof s.step === 'number' ? s.step : -1; })(window.history.state)")

private fun currentOpener(): String? = js("(function (s) { return s && typeof s.opener === 'string' ? s.opener : null; })(history.state)")

@Suppress("UNUSED_PARAMETER")
private fun pushState(step: Int, path: String): Unit = js("window.history.pushState({ step: step }, '', path)")

/** An undefined URL keeps the query and fragment. */
@Suppress("UNUSED_PARAMETER")
private fun replaceState(step: Int, opener: String?, path: String?): Unit =
    js("window.history.replaceState({ step: step, opener: opener }, '', path === null ? undefined : path)")

private fun historyBack(): Unit = js("window.history.back()")

@Suppress("UNUSED_PARAMETER")
private fun addPopStateListener(handler: () -> Unit): Unit = js("window.addEventListener('popstate', function () { handler(); })")

private fun windowScrollToTop(): Unit = js("window.scrollTo(0, 0)")

/**
 * Routing through the History API: the [shown] page follows the URL; Back, Forward and reloads work. Every page opened is a new history
 * entry (as on desktop, docs/architecture.md). Each entry records its step, so a Back from the first page opened in the tab shows that
 * page's parent rather than leaving the app, and what opened the next page, so focus goes back there on return.
 */
internal class BrowserRouter(private val history: BrowserHistory = WindowHistory) {
    private var step = history.step
    private var visit = 0

    private val _shown = MutableStateFlow(Shown(Route.parse(history.path), opener = null, visit))
    val shown: StateFlow<Shown> = _shown.asStateFlow()

    private val route: Route get() = _shown.value.route

    init {
        // A reload keeps the entry's state; a fresh tab gets step 0.
        if (step < 0) {
            step = 0
            history.replace(step, opener = null, path = null)
        }
        history.onPopState {
            step = history.step.coerceAtLeast(0)
            show(Route.parse(history.path), history.opener)
        }
    }

    /**
     * Opens [route] as a new history entry, recording [opener] in the entry left. The page already shown adds none and changes nothing
     * (a double click, or its sidebar entry, opens it once).
     */
    fun navigate(route: Route, opener: String?) {
        // Parsed, so `/settings/` counts as Settings (W6).
        if (route == Route.parse(history.path)) return
        history.replace(step, opener, path = null)
        step += 1
        history.push(step, route.path)
        show(route, opener = null)
        history.scrollToTop()
    }

    /** Shows [route] in place of the current entry, so Back skips the page it replaces (a finished step, such as adding a server). */
    fun replace(route: Route) {
        history.replace(step, opener = null, route.path)
        show(route, opener = null)
        history.scrollToTop()
    }

    fun back() {
        when (val next = backStep(step, route)) {
            BackStep.History -> history.back()
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
