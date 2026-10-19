package com.skeksik.current.spike.web

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.events.Event

private fun importHls(onLoaded: (JsAny) -> Unit, onFailed: (String) -> Unit): Unit =
    js("import('hls.js').then(function (m) { onLoaded(m.default || m); }, function (e) { onFailed(String(e)); })")

private fun hlsIsSupported(hlsClass: JsAny): Boolean = js("hlsClass.isSupported()")

/**
 * Jellyfin holds a segment request until ffmpeg has written it, so a busy or software-only transcode answers late: wait up to a minute
 * for the first byte (hls.js waits 10 s by default), and recover from network and media errors a few times before giving up.
 */
private fun hlsAttach(hlsClass: JsAny, video: HTMLVideoElement, url: String, ranges: String, onFatal: (String) -> Unit): JsAny = js(
    """(function () {
        var slow = { maxTimeToFirstByteMs: 60000, maxLoadTimeMs: 180000,
                     timeoutRetry: { maxNumRetry: 4, retryDelayMs: 0, maxRetryDelayMs: 0 },
                     errorRetry: { maxNumRetry: 6, retryDelayMs: 1000, maxRetryDelayMs: 8000 } };
        var allowed = ranges.split(',');
        var hls = new hlsClass({ enableWorker: true, fragLoadPolicy: { default: slow }, manifestLoadPolicy: { default: slow },
                                 playlistLoadPolicy: { default: slow },
                                 videoPreference: { preferHDR: allowed.length > 1, allowedVideoRanges: allowed } });
        var recoveries = 0;
        hls.on(hlsClass.Events.ERROR, function (event, data) {
            if (!data.fatal) return;
            if (recoveries < 3 && data.type === hlsClass.ErrorTypes.NETWORK_ERROR) { recoveries++; hls.startLoad(); return; }
            if (recoveries < 3 && data.type === hlsClass.ErrorTypes.MEDIA_ERROR) { recoveries++; hls.recoverMediaError(); return; }
            onFatal('hls.js: ' + data.type + ' / ' + data.details);
        });
        hls.on(hlsClass.Events.FRAG_BUFFERED, function () { recoveries = 0; });
        hls.loadSource(url);
        hls.attachMedia(video);
        return hls;
    })()""",
)

private fun hlsDestroy(hls: JsAny): Unit = js("hls.destroy()")
private fun playQuietly(video: HTMLVideoElement): Unit = js("(function () { var p = video.play(); if (p) p.catch(function () {}); })()")
private fun mediaErrorText(video: HTMLVideoElement): String =
    js("video.error ? ('Media error ' + video.error.code + (video.error.message ? ': ' + video.error.message : '')) : ''")
private fun isFullscreen(element: HTMLElement): Boolean = js("document.fullscreenElement === element")
private fun exitFullscreenOf(element: HTMLElement): Unit =
    js("(function () { if (document.fullscreenElement === element) { var p = document.exitFullscreen(); if (p) p.catch(function () {}); } })()")
private fun toggleFullscreenOf(element: HTMLElement): Unit = js(
    """(function () {
        var p = document.fullscreenElement ? document.exitFullscreen() : element.requestFullscreen();
        if (p) p.catch(function () {});
    })()""",
)

private fun setMediaSession(title: String, artist: String, artwork: String, onAction: (String, Double) -> Unit): Unit = js(
    """(function () {
        if (!('mediaSession' in navigator)) return;
        navigator.mediaSession.metadata = new MediaMetadata({ title: title, artist: artist, artwork: artwork ? [{ src: artwork }] : [] });
        ['play', 'pause', 'seekbackward', 'seekforward', 'seekto'].forEach(function (action) {
            try {
                navigator.mediaSession.setActionHandler(action, function (d) {
                    onAction(action, d && d.seekTime != null ? d.seekTime : (d && d.seekOffset != null ? d.seekOffset : -1));
                });
            } catch (e) {}
        });
    })()""",
)

private fun clearMediaSession(): Unit = js(
    """(function () {
        if (!('mediaSession' in navigator)) return;
        navigator.mediaSession.metadata = null;
        ['play', 'pause', 'seekbackward', 'seekforward', 'seekto'].forEach(function (a) { try { navigator.mediaSession.setActionHandler(a, null); } catch (e) {} });
    })()""",
)

private fun addTextTrack(video: HTMLVideoElement, url: String): Unit = js(
    """(function () {
        var track = document.createElement('track');
        track.kind = 'subtitles';
        track.src = url;
        track.default = true;
        track.setAttribute('data-current', '');
        video.appendChild(track);
        track.addEventListener('load', function () { track.track.mode = 'showing'; });
        track.track.mode = 'showing';
    })()""",
)

private fun removeTextTracks(video: HTMLVideoElement): Unit =
    js("video.querySelectorAll('track[data-current]').forEach(function (t) { t.remove(); })")

private fun bufferedEnd(video: HTMLVideoElement): Double = js("video.buffered.length ? video.buffered.end(video.buffered.length - 1) : 0")

/**
 * The headless web engine (ADR 0007): plays into a page's `<video>`, through hls.js when the server sends HLS (native HLS only where
 * hls.js cannot run), and fullscreens the page's player [container] so our controls stay on top. Media Session gets metadata and actions.
 */
class HtmlVideoEngine(private val video: HTMLVideoElement, private val container: HTMLElement) : PlayerEngine {
    private val _state = MutableStateFlow(EngineState(volume = video.volume, muted = video.muted))
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private var hls: JsAny? = null
    private var loadGeneration = 0
    private var pendingStart = 0.0
    private val videoListeners = mutableListOf<Pair<String, (Event) -> Unit>>()
    private val fullscreenListener: (Event) -> Unit = { _state.update { it.copy(fullscreen = isFullscreen(container)) } }

    init {
        // Cross-origin <track> files need CORS mode; Jellyfin allows any origin.
        video.setAttribute("crossorigin", "anonymous")
        video.setAttribute("playsinline", "")
        on("timeupdate") { _state.update { it.copy(positionSeconds = video.currentTime) } }
        on("durationchange") { val d = video.duration; if (d.isFinite()) _state.update { it.copy(durationSeconds = d) } }
        on("progress") { _state.update { it.copy(bufferedSeconds = bufferedEnd(video)) } }
        on("play") { _state.update { it.copy(paused = false, ended = false) } }
        on("pause") { _state.update { it.copy(paused = true) } }
        on("waiting") { _state.update { it.copy(buffering = true) } }
        on("playing") { _state.update { it.copy(buffering = false, paused = false) } }
        on("canplay") { _state.update { it.copy(buffering = false) } }
        on("ended") { _state.update { it.copy(ended = true, paused = true) } }
        on("volumechange") { _state.update { it.copy(volume = video.volume, muted = video.muted) } }
        on("loadedmetadata") {
            if (pendingStart > 0) video.currentTime = pendingStart
            pendingStart = 0.0
            playQuietly(video)
        }
        on("error") { val text = mediaErrorText(video); if (text.isNotEmpty()) _state.update { it.copy(error = text) } }
        kotlinx.browser.document.addEventListener("fullscreenchange", fullscreenListener)
    }

    private fun on(type: String, listener: (Event) -> Unit) {
        video.addEventListener(type, listener)
        videoListeners += type to listener
    }

    override fun load(url: String, isHls: Boolean, startSeconds: Double, subtitleUrl: String?, videoRanges: List<String>) {
        val generation = ++loadGeneration
        detachSource()
        pendingStart = startSeconds
        _state.update { it.copy(error = null, ended = false, buffering = true, positionSeconds = startSeconds) }
        if (subtitleUrl != null) addTextTrack(video, subtitleUrl)
        if (!isHls) {
            video.src = url
            return
        }
        importHls(
            onLoaded = { hlsClass ->
                if (generation == loadGeneration) {
                    if (hlsIsSupported(hlsClass)) {
                        hls = hlsAttach(hlsClass, video, url, videoRanges.joinToString(",")) { message -> _state.update { it.copy(error = message) } }
                    } else {
                        video.src = url // Native HLS (Safari, iOS)
                    }
                }
            },
            onFailed = { message -> _state.update { it.copy(error = "Cannot load hls.js: $message") } },
        )
    }

    override fun play() = playQuietly(video)
    override fun pause() = video.pause()
    override fun seekTo(seconds: Double) {
        video.currentTime = seconds
        _state.update { it.copy(positionSeconds = seconds) }
    }

    override fun setVolume(volume: Double) {
        video.volume = volume
    }

    override fun setMuted(muted: Boolean) {
        video.muted = muted
    }

    override fun toggleFullscreen() = toggleFullscreenOf(container)

    override fun setMetadata(title: String, artist: String?, artworkUrl: String?) {
        setMediaSession(title, artist.orEmpty(), artworkUrl.orEmpty()) { action, value ->
            when (action) {
                "play" -> play()
                "pause" -> pause()
                "seekbackward" -> seekTo(video.currentTime - (if (value > 0) value else 10.0))
                "seekforward" -> seekTo(video.currentTime + (if (value > 0) value else 10.0))
                "seekto" -> if (value >= 0) seekTo(value)
            }
        }
    }

    override fun release() {
        // Leaving the player (Back, a link) must end its fullscreen; where the container outlives the page (the whole document in
        // the canvas prototype), the browser would otherwise stay fullscreen.
        exitFullscreenOf(container)
        loadGeneration++
        detachSource()
        videoListeners.forEach { (type, listener) -> video.removeEventListener(type, listener) }
        videoListeners.clear()
        kotlinx.browser.document.removeEventListener("fullscreenchange", fullscreenListener)
        clearMediaSession()
    }

    private fun detachSource() {
        hls?.let { hlsDestroy(it) }
        hls = null
        removeTextTracks(video)
        video.removeAttribute("src")
        video.load()
    }
}

private fun addKeyListeners(onKey: (String) -> Boolean): JsAny = js(
    """(function () {
        function typing(e) { var t = e.target; return t && (t.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName) && t.type !== 'range'); }
        var handled = {};
        function down(e) {
            if (e.ctrlKey || e.metaKey || e.altKey || typing(e)) return;
            if (onKey(e.key)) { e.preventDefault(); e.stopPropagation(); handled[e.key] = true; }
        }
        function up(e) { if (handled[e.key]) { e.preventDefault(); e.stopPropagation(); delete handled[e.key]; } }
        document.addEventListener('keydown', down, true);
        document.addEventListener('keyup', up, true);
        return function () { document.removeEventListener('keydown', down, true); document.removeEventListener('keyup', up, true); };
    })()""",
)

private fun callDispose(dispose: JsAny): Unit = js("dispose()")

/**
 * Player shortcuts for the whole page, in the capture phase: a focused button (Space) or seek bar (arrows) never handles the same key
 * again (ADR 0010). Typing in text fields is left alone. Close the result to remove the listeners.
 */
fun attachPlayerKeys(viewModel: PlayerViewModel): AutoCloseable {
    val dispose = addKeyListeners { key -> viewModel.onKey(key) }
    return AutoCloseable { callDispose(dispose) }
}
