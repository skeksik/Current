package com.skeksik.current.spike.web

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What a headless engine reports (ADR 0007): it plays and renders, the controls are ours. */
data class EngineState(
    val positionSeconds: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val bufferedSeconds: Double = 0.0,
    val paused: Boolean = true,
    val buffering: Boolean = false,
    val ended: Boolean = false,
    val volume: Double = 1.0,
    val muted: Boolean = false,
    val fullscreen: Boolean = false,
    val error: String? = null,
)

/** The `<video>` (+ hls.js) engine in the browser; a fake in tests. */
interface PlayerEngine {
    val state: StateFlow<EngineState>
    fun load(url: String, isHls: Boolean, startSeconds: Double, subtitleUrl: String?)
    fun play()
    fun pause()
    fun seekTo(seconds: Double)
    fun setVolume(volume: Double)
    fun setMuted(muted: Boolean)
    fun toggleFullscreen()
    fun setMetadata(title: String, artist: String?, artworkUrl: String?)
    fun release()
}

enum class PlayerMenu { Audio, Subtitles }

data class PlayerUiState(
    val title: String = "",
    val subtitle: String? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val method: PlayMethod? = null,
    val transcodeReasons: String? = null,
    val audioTracks: List<Track> = emptyList(),
    val subtitleTracks: List<Track> = emptyList(),
    val audioIndex: Int? = null,
    val subtitleIndex: Int = SUBTITLES_OFF,
    val playback: EngineState = EngineState(),
    val controlsVisible: Boolean = true,
    val openMenu: PlayerMenu? = null,
) {
    val positionText get() = formatTime(playback.positionSeconds)
    val durationText get() = formatTime(playback.durationSeconds)
}

/**
 * The player controller: asks the server for a route (PlaybackInfo with the browser's device profile), loads it into the engine, switches
 * tracks by asking again, keeps HLS transcodes alive with pings and stops them when the page closes. Never reports progress (spike rule).
 */
class PlayerViewModel internal constructor(private val graph: AppGraph, private val itemId: String) : ViewModel() {
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val session = graph.session.value
    private val api = session?.let { graph.api(it) }
    private var engine: PlayerEngine? = null
    private var engineJob: Job? = null
    private var hideJob: Job? = null
    private var source: PlaybackSource? = null
    private var choice = TrackChoice()
    private var startSeconds = 0.0
    private var artworkUrl: String? = null
    private var triedServerAfterError = false

    init {
        if (session == null || api == null) {
            _state.update { it.copy(loading = false, error = "Not signed in.") }
        } else {
            viewModelScope.launch {
                attempt { api.item(session.userId, itemId) }.onSuccess { item ->
                    artworkUrl = item.imageUrl
                    _state.update { it.copy(title = item.name, subtitle = item.seriesName) }
                    engine?.setMetadata(item.name, item.seriesName, item.imageUrl)
                }
            }
            request(TrackChoice(), forceServer = false)
            viewModelScope.launch {
                while (isActive) {
                    delay(PING_INTERVAL_MS)
                    source?.takeIf { it.isHls }?.playSessionId?.let { id -> attempt { api.ping(id) } }
                }
            }
        }
    }

    /** Called by the page once its `<video>` exists. */
    fun attach(engine: PlayerEngine) {
        detach()
        this.engine = engine
        engineJob = viewModelScope.launch {
            engine.state.collect { playback ->
                val wasPlaying = !_state.value.playback.paused
                _state.update { it.copy(playback = playback) }
                if (playback.paused) showControls() else if (!wasPlaying) onActivity()
                if (playback.error != null) onEngineError(playback.error)
            }
        }
        engine.setMetadata(_state.value.title, _state.value.subtitle, artworkUrl)
        source?.let { engine.load(it.url, it.isHls, startSeconds, it.subtitleUrl) }
    }

    fun detach() {
        engineJob?.cancel()
        engineJob = null
        engine?.release()
        engine = null
    }

    fun togglePlay() {
        val engine = engine ?: return
        if (_state.value.playback.paused) engine.play() else engine.pause()
    }

    fun seekTo(seconds: Double) {
        val duration = _state.value.playback.durationSeconds
        engine?.seekTo(seconds.coerceIn(0.0, if (duration > 0) duration else Double.MAX_VALUE))
        onActivity()
    }

    fun seekBy(deltaSeconds: Double) = seekTo(_state.value.playback.positionSeconds + deltaSeconds)

    fun setVolume(volume: Double) {
        engine?.setVolume(volume.coerceIn(0.0, 1.0))
        if (volume > 0 && _state.value.playback.muted) engine?.setMuted(false)
    }

    fun toggleMute() = engine?.setMuted(!_state.value.playback.muted)

    fun toggleFullscreen() = engine?.toggleFullscreen()

    fun toggleMenu(menu: PlayerMenu) {
        _state.update { it.copy(openMenu = if (it.openMenu == menu) null else menu) }
        showControls()
    }

    fun closeMenu() = _state.update { it.copy(openMenu = null) }

    fun selectAudio(index: Int) {
        closeMenu()
        if (index == _state.value.audioIndex) return
        switchTracks(choice.copy(audioIndex = index))
    }

    fun selectSubtitle(index: Int) {
        closeMenu()
        if (index == _state.value.subtitleIndex) return
        switchTracks(choice.copy(subtitleIndex = index))
    }

    /** Space/K, arrows, F, M. Returns true when handled, so the page can call `preventDefault`. */
    fun onKey(key: String): Boolean {
        val handled = when (key) {
            " ", "k", "K" -> { togglePlay(); true }
            "ArrowLeft" -> { seekBy(-SEEK_STEP); true }
            "ArrowRight" -> { seekBy(SEEK_STEP); true }
            "ArrowUp" -> { setVolume(_state.value.playback.volume + 0.1); true }
            "ArrowDown" -> { setVolume(_state.value.playback.volume - 0.1); true }
            "f", "F" -> { toggleFullscreen(); true }
            "m", "M" -> { toggleMute(); true }
            "Escape" -> if (_state.value.openMenu != null) { closeMenu(); true } else false
            else -> false
        }
        if (handled) onActivity()
        return handled
    }

    /** Pointer or key activity: show the controls, hide them again after a while if playing and no menu is open. */
    fun onActivity() {
        showControls()
        hideJob = viewModelScope.launch {
            delay(HIDE_AFTER_MS)
            val s = _state.value
            if (!s.playback.paused && s.openMenu == null) _state.update { it.copy(controlsVisible = false) }
        }
    }

    private fun showControls() {
        hideJob?.cancel()
        if (!_state.value.controlsVisible) _state.update { it.copy(controlsVisible = true) }
    }

    private fun switchTracks(newChoice: TrackChoice) {
        startSeconds = _state.value.playback.positionSeconds
        request(newChoice, forceServer = false)
    }

    /** ADR 0007: if direct play fails at runtime (a probe said yes, the browser said no), retry once through the server. */
    private fun onEngineError(message: String) {
        if (source?.method == PlayMethod.DirectPlay && !triedServerAfterError) {
            triedServerAfterError = true
            startSeconds = _state.value.playback.positionSeconds
            request(choice, forceServer = true)
        } else {
            _state.update { it.copy(error = message) }
        }
    }

    private fun request(newChoice: TrackChoice, forceServer: Boolean) {
        val session = session ?: return
        val api = api ?: return
        val previous = source
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val request = PlaybackPlanner.request(session.userId, graph.deviceProfile(), newChoice, previous?.defaultAudioIndex)
                .let { if (forceServer) it.copy(enableDirectPlay = false) else it }
            val planned = attempt { api.playbackInfo(itemId, request) }
                .mapCatching { info ->
                    PlaybackPlanner.plan(session.serverUrl, session.accessToken, graph.clientInfo.deviceId, itemId, info, newChoice).getOrThrow()
                }
            planned.onSuccess { next ->
                source = next
                choice = newChoice.copy(mediaSourceId = next.mediaSourceId)
                _state.update {
                    it.copy(
                        loading = false,
                        method = next.method,
                        transcodeReasons = next.transcodeReasons,
                        audioTracks = next.audioTracks,
                        subtitleTracks = next.subtitleTracks,
                        audioIndex = next.audioIndex,
                        subtitleIndex = next.subtitleIndex,
                    )
                }
                engine?.load(next.url, next.isHls, startSeconds, next.subtitleUrl)
                val old = previous?.playSessionId
                if (previous?.isHls == true && old != null && old != next.playSessionId) graph.launchDetached { api.stopEncoding(old) }
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = (e as? IllegalStateException)?.message ?: e.userMessage()) }
            }
        }
    }

    override fun onCleared() {
        detach()
        val last = source
        val api = api
        if (last?.isHls == true && last.playSessionId != null && api != null) graph.launchDetached { api.stopEncoding(last.playSessionId) }
    }

    companion object {
        const val SEEK_STEP = 10.0
        const val PING_INTERVAL_MS = 10_000L
        const val HIDE_AFTER_MS = 3_000L
    }
}
