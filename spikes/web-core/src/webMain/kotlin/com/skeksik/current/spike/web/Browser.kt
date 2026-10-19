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
import kotlinx.coroutines.async
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class LocalStorageKeyValueStore : KeyValueStore {
    override fun get(key: String): String? = localStorage.getItem(key)
    override fun set(key: String, value: String?) {
        if (value == null) localStorage.removeItem(key) else localStorage.setItem(key, value)
    }
}

private fun userAgent(): String = js("navigator.userAgent")

/** Runs [BrowserProbes.queries] (JSON) and calls back with `{id: answer}` (JSON) once the asynchronous ones have answered. */
private fun runProbes(queriesJson: String, onDone: (String) -> Unit): Unit = js(
    """(function () {
        var queries = JSON.parse(queriesJson), out = {}, pending = [], video = document.createElement('video');
        queries.forEach(function (q) {
            try {
                if (q.kind === 'CanPlayType') out[q.id] = video.canPlayType(q.arg);
                else if (q.kind === 'IsTypeSupported') out[q.id] = String(typeof MediaSource !== 'undefined' && MediaSource.isTypeSupported(q.arg));
                else if (q.kind === 'MatchMedia') out[q.id] = String(window.matchMedia(q.arg).matches);
                else if (q.kind === 'MaxChannels') {
                    var Ctx = window.AudioContext || window.webkitAudioContext;
                    if (Ctx) { var ctx = new Ctx(); out[q.id] = String(ctx.destination.maxChannelCount); ctx.close(); } else out[q.id] = '2';
                } else if (q.kind === 'DecodingInfo') {
                    if (!navigator.mediaCapabilities) { out[q.id] = 'unavailable'; return; }
                    pending.push(navigator.mediaCapabilities.decodingInfo(JSON.parse(q.arg)).then(
                        function (r) { out[q.id] = r.supported ? (r.smooth ? 'smooth' : 'supported') : 'false'; },
                        function () { out[q.id] = 'error'; }));
                }
            } catch (e) { out[q.id] = 'error'; }
        });
        Promise.all(pending).then(function () {
            window.__currentProbes = out;
            onDone(JSON.stringify(out));
        });
    })()""",
)

/** Every runtime probe of [BrowserProbes] (`canPlayType`, `isTypeSupported`, `decodingInfo`, `matchMedia`, the audio output). */
suspend fun probeMediaSupport(): MediaSupport = suspendCoroutine { continuation ->
    val queries = buildJsonArray {
        BrowserProbes.queries.forEach { q -> add(buildJsonObject { put("id", q.id); put("kind", q.kind.name); put("arg", q.arg) }) }
    }
    runProbes(queries.toString()) { json ->
        val answers = Json.parseToJsonElement(json).jsonObject.mapValues { it.value.jsonPrimitive.content }
        continuation.resume(BrowserProbes.interpret(answers))
    }
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
            // Probing starts now and runs while the first pages load; the player awaits it before asking for a route.
            val support = MainScope().async { probeMediaSupport() }
            return WebApp(AppGraph(LocalStorageKeyValueStore(), Js.create(), support, browserName()), BrowserRouter())
        }
    }
}
