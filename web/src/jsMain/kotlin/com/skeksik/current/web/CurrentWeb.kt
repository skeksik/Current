package com.skeksik.current.web

import com.skeksik.current.core.playback.web.probeMediaSupport
import com.skeksik.current.feature.settings.PlaybackSupportViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async

/** The current page: [kind] is "home", "playbackSupport" or "notFound". */
@JsExport
class RouteView(val kind: String, val path: String)

/** The whole core as the React app sees it; one per page load. */
@JsExport
class CurrentWeb {
    private val router = BrowserRouter()

    /** The browser probes run once per page load, when a page first needs them. */
    private val mediaSupport = mainScope.async(start = CoroutineStart.LAZY) { probeMediaSupport() }

    val route: Observable<RouteView> = router.route.observe { it.toView() }

    /** Navigates for a plain click and returns true (call `preventDefault`); leaves other clicks to the browser. */
    fun onLinkClick(path: String, button: Int, ctrl: Boolean, meta: Boolean, shift: Boolean, alt: Boolean): Boolean {
        if (!isPlainClick(button, ctrl, meta, shift, alt)) return false
        router.navigate(Route.parse(path))
        return true
    }

    fun playbackSupportPage() = PlaybackSupportPage(holdViewModel { PlaybackSupportViewModel { mediaSupport.await() } })
}

private fun Route.toView() = when (this) {
    Route.Home -> RouteView("home", path)
    Route.PlaybackSupport -> RouteView("playbackSupport", path)
    is Route.NotFound -> RouteView("notFound", path)
}
