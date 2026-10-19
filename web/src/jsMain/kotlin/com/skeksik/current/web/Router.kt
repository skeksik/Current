package com.skeksik.current.web

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App URLs. Every page has its own path, so links work with middle-click, new tabs, copy link and reloads. */
internal sealed interface Route {
    val path: String

    data object Home : Route {
        override val path = "/"
    }

    data object PlaybackSupport : Route {
        override val path = "/settings/playback"
    }

    data class NotFound(override val path: String) : Route

    companion object {
        fun parse(path: String): Route = when (path.substringBefore('?').substringBefore('#').trimEnd('/')) {
            "" -> Home
            PlaybackSupport.path -> PlaybackSupport
            else -> NotFound(path)
        }
    }
}

private fun locationPath(): String = js("window.location.pathname")

@Suppress("UNUSED_PARAMETER")
private fun pushState(path: String): Unit = js("window.history.pushState(null, '', path)")

@Suppress("UNUSED_PARAMETER")
private fun onPopState(handler: () -> Unit): Unit = js("window.addEventListener('popstate', function () { handler(); })")

private fun scrollToTop(): Unit = js("window.scrollTo(0, 0)")

/** Routing through the History API: the current [route] follows the URL; Back, Forward and reloads work. */
internal class BrowserRouter {
    private val _route = MutableStateFlow(Route.parse(locationPath()))
    val route: StateFlow<Route> = _route.asStateFlow()

    init {
        onPopState { _route.value = Route.parse(locationPath()) }
    }

    fun navigate(route: Route) {
        if (route.path != locationPath()) pushState(route.path)
        _route.value = route
        scrollToTop()
    }
}

/** A plain primary click is handled by the app; anything else (middle-click, Ctrl/Cmd/Shift/Alt) is left to the browser. */
internal fun isPlainClick(button: Int, ctrl: Boolean, meta: Boolean, shift: Boolean, alt: Boolean): Boolean =
    button == 0 && !ctrl && !meta && !shift && !alt
