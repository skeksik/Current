package com.skeksik.current.core.playback.web

import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.js.Promise
import kotlin.js.json

/** Runs the queries (`[{id, kind, arg}]`) and resolves to `{id: answer}` once the asynchronous ones have answered. */
@Suppress("UNUSED_PARAMETER")
private fun runQueries(queries: Array<dynamic>): Promise<Any> = js(
    """(function () {
        var out = {}, pending = [], video = document.createElement('video');
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
        return Promise.all(pending).then(function () { return out; });
    })()""",
)

private fun keys(o: dynamic): Array<String> = js("Object.keys(o)")

/**
 * Runs every probe of [BrowserProbes] in this page (`canPlayType`, `MediaSource.isTypeSupported`, `decodingInfo`, `matchMedia`, the
 * audio output's channels) and interprets the answers. It never fails: a probe that throws answers "error", which counts as no.
 */
suspend fun probeMediaSupport(): MediaSupport {
    val queries = BrowserProbes.queries.map { json("id" to it.id, "kind" to it.kind.name, "arg" to it.arg).asDynamic() }.toTypedArray()
    val out = suspendCoroutine { continuation -> runQueries(queries).then { value: Any -> continuation.resume(value.asDynamic()) } }
    val answers = keys(out).associateWith { key -> out[key].unsafeCast<String>() }
    return BrowserProbes.interpret(answers)
}
