package com.skeksik.current.spike.web

import kotlinx.browser.localStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import io.ktor.client.engine.js.Js
import kotlinx.browser.window

class LocalStorageKeyValueStore : KeyValueStore {
    override fun get(key: String): String? = localStorage.getItem(key)
    override fun set(key: String, value: String?) {
        if (value == null) localStorage.removeItem(key) else localStorage.setItem(key, value)
    }
}

private fun canPlayType(type: String): String = js("document.createElement('video').canPlayType(type)")
private fun isTypeSupported(type: String): Boolean = js("typeof MediaSource !== 'undefined' && MediaSource.isTypeSupported(type)")
private fun hasMediaSource(): Boolean = js("typeof MediaSource !== 'undefined' || typeof ManagedMediaSource !== 'undefined'")
private fun userAgent(): String = js("navigator.userAgent")

/** Runtime probes, as jellyfin-web does: `canPlayType` decides direct play through `src`, `isTypeSupported` what hls.js can feed. */
fun probeMediaSupport(): MediaSupport {
    val probes = linkedMapOf<String, String>()
    fun play(type: String): Boolean = canPlayType(type).also { probes["canPlayType($type)"] = it.ifEmpty { "\"\"" } }.isNotEmpty()
    fun mse(type: String): Boolean = isTypeSupported(type).also { probes["isTypeSupported($type)"] = it.toString() }
    val mkvPlay = play("video/x-matroska; codecs=\"avc1.640029, mp4a.40.2\"") || play("video/x-matroska")
    mse("video/x-matroska; codecs=\"avc1.640029, mp4a.40.2\"")
    val support = MediaSupport(
        h264 = play("video/mp4; codecs=\"avc1.640029\""),
        hevc = play("video/mp4; codecs=\"hvc1.1.6.L120.90\"") || play("video/mp4; codecs=\"hev1.1.6.L120.90\""),
        av1 = play("video/mp4; codecs=\"av01.0.08M.08\""),
        vp9 = play("video/webm; codecs=\"vp9\""),
        vp8 = play("video/webm; codecs=\"vp8\""),
        aac = play("audio/mp4; codecs=\"mp4a.40.2\""),
        mp3 = play("audio/mpeg"),
        opus = play("audio/webm; codecs=\"opus\""),
        flac = play("audio/flac"),
        vorbis = play("audio/webm; codecs=\"vorbis\""),
        mkv = mkvPlay,
        nativeHls = play("application/vnd.apple.mpegurl"),
        mse = hasMediaSource() && mse("video/mp4; codecs=\"avc1.42E01E, mp4a.40.2\""),
        probes = probes,
    )
    mse("video/mp4; codecs=\"hvc1.1.6.L120.90\"")
    mse("audio/mp4; codecs=\"ac-3\"")
    mse("audio/mp4; codecs=\"ec-3\"")
    play("audio/mp4; codecs=\"ac-3\"")
    play("audio/mp4; codecs=\"ec-3\"")
    return support.copy(probes = probes)
}

private fun browserName(): String {
    val ua = userAgent()
    return when {
        "Edg/" in ua -> "Edge"
        "Firefox/" in ua -> "Firefox"
        "Chrome/" in ua -> "Chrome"
        "Safari/" in ua -> "Safari"
        else -> "Web browser"
    }
}

private fun locationPath(): String = js("window.location.pathname")
private fun pushState(path: String): Unit = js("window.history.pushState(null, '', path)")
private fun replaceState(path: String): Unit = js("window.history.replaceState(null, '', path)")
private fun scrollToTop(): Unit = js("window.scrollTo(0, 0)")

/** Routing through the History API: every page is a real URL; Back, Forward and reloads work. */
class BrowserRouter {
    private val _route = MutableStateFlow(Route.parse(locationPath()))
    val route: StateFlow<Route> = _route.asStateFlow()

    init {
        window.addEventListener("popstate", { _route.value = Route.parse(locationPath()) })
    }

    fun navigate(route: Route) {
        if (route.path != locationPath()) pushState(route.path)
        _route.value = route
        scrollToTop()
    }

    fun replace(route: Route) {
        replaceState(route.path)
        _route.value = route
    }
}

/** The core as each UI uses it: the graph, the router, and what to show for the current URL and session. */
class WebApp(val graph: AppGraph, val router: BrowserRouter, scope: CoroutineScope = MainScope()) {
    /** Sign-in while signed out (the URL is kept, so the page opens after signing in); never the sign-in page while signed in. */
    val screen: StateFlow<Route> = combine(graph.session, router.route) { session, route -> if (session == null) Route.SignIn else route }
        .stateIn(scope, SharingStarted.Eagerly, if (graph.session.value == null) Route.SignIn else router.route.value)

    init {
        graph.session.onEach { if (it != null && router.route.value == Route.SignIn) router.replace(Route.Home) }.launchIn(scope)
    }

    companion object {
        fun create(): WebApp {
            val support = probeMediaSupport()
            return WebApp(AppGraph(LocalStorageKeyValueStore(), Js.create(), support, browserName()), BrowserRouter())
        }
    }
}
