package com.skeksik.current.spike.desktopvideo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.openani.mediamp.mpv.MPVHandle
import org.openani.mediamp.mpv.MpvMediampPlayer
import org.openani.mediamp.mpv.compose.MpvMediampPlayerSurface
import org.openani.mediamp.playUri
import java.io.File
import java.io.PrintWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Candidate 1: MediaMP's libmpv player, which renders into D3D11 textures that Compose's own Direct3D 12 renderer draws, so our
 * controls are ordinary Compose content above the video in the same window. Only measured briefly: it outputs 8-bit SDR.
 *
 * Play/pause and seeking go through MediaMP's state machine (it tracks its own seeks); tracks and stats read and set mpv
 * properties on MediaMP's handle directly. State is polled, which is enough for a short measurement.
 */
class TexturePlayer(private val request: PlayRequest, private val scope: CoroutineScope) : SpikePlayer, AutoCloseable {
    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()
    override val rejectedOptions = mutableListOf<String>()

    private val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    private val log = PrintWriter(File(SpikePaths.outDir, "texture-$stamp.log").bufferedWriter(), true)

    val mediamp: MpvMediampPlayer
    private val handle: MPVHandle
    @Volatile private var closed = false
    private var poller: Job? = null

    init {
        // The same LGPL DLLs as candidate 2 (extracted by the build), so one process never loads two copies of libmpv.
        MpvMediampPlayer.prepareLibraries(SpikePaths.mpvDir.absolutePath, extractRuntimeLibrary = false)
        mediamp = MpvMediampPlayer(Unit, scope.coroutineContext, configureOptions = { h ->
            fun set(name: String, value: String) {
                if (!h.option(name, value)) rejectedOptions += "$name=$value"
            }
            set("hwdec", request.hwdec)
            set("media-controls", if (request.mediaControls) "yes" else "no")
            set("audio-fallback-to-null", "yes")
            request.parsedExtraOptions().forEach { (k, v) -> set(k, v) }
        })
        handle = mediamp.impl as MPVHandle
        note("candidate 1 (MediaMP texture), request: ${redact(request.toString())}")
        Diagnostics.event("texture: player created, loading ${displayName(request.source)}")
    }

    fun start() {
        scope.launch {
            try {
                mediamp.playUri(request.source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Diagnostics.crash("texture: playUri", e)
                _state.value = _state.value.copy(error = "Playback failed: ${e.message}")
            }
        }
        poller = scope.launch(Dispatchers.IO) {
            var trackCount = -1
            var aid: String? = null
            var sid: String? = null
            while (isActive) {
                val count = getString("track-list/count")?.toIntOrNull() ?: 0
                val newAid = getString("aid")
                val newSid = getString("sid")
                val tracksChanged = count != trackCount || newAid != aid || newSid != sid
                trackCount = count
                aid = newAid
                sid = newSid
                _state.value = _state.value.copy(
                    paused = getString("pause") == "yes",
                    positionSeconds = getString("time-pos")?.toDoubleOrNull() ?: 0.0,
                    durationSeconds = getString("duration")?.toDoubleOrNull() ?: 0.0,
                    buffering = getString("paused-for-cache") == "yes",
                    endReached = getString("eof-reached") == "yes",
                    tracks = if (tracksChanged) readTracks(::getString) else _state.value.tracks,
                )
                delay(250)
            }
        }
    }

    override fun togglePause() {
        if (state.value.paused) mediamp.play() else mediamp.pause()
    }

    override fun seekTo(seconds: Double) = mediamp.seekTo((seconds * 1000).toLong())

    override fun seekBy(seconds: Double) = seekTo((state.value.positionSeconds + seconds).coerceAtLeast(0.0))

    override fun selectAudio(id: Int) = setProperty("aid", id.toString())

    override fun selectSubtitle(id: Int?) = setProperty("sid", id?.toString() ?: "no")

    override fun getString(name: String): String? = if (closed) null else handle.getPropertyString(name)

    override fun note(message: String) {
        log.println("${LocalDateTime.now()} [spike] ${redact(message)}")
    }

    private fun setProperty(name: String, value: String) {
        if (!closed && !handle.setPropertyString(name, value)) note("set $name=$value failed")
    }

    override fun close() {
        if (closed) return
        Diagnostics.event("texture: closing")
        poller?.cancel()
        closed = true
        mediamp.close()
        log.close()
    }
}

/** Candidate 1's player window: the video is a Compose node, the controls are drawn over it in the same window. */
@Composable
fun TexturePlayerWindow(request: PlayRequest, onClosed: () -> Unit) {
    val windowState = rememberWindowState(width = 1280.dp, height = 760.dp)
    val overlay = remember { OverlayState().apply { showStats = request.stats } }
    val scope = rememberCoroutineScope()
    val player = remember { TexturePlayer(request, scope).also { it.start() } }
    DisposableEffect(player) { onDispose { player.close() } }
    val close = {
        Diagnostics.event("texture window: close requested")
        onClosed()
    }

    Window(
        onCloseRequest = close,
        state = windowState,
        title = "Spike (texture): ${displayName(request.source)}",
        onKeyEvent = { handleKey(it, player, windowState, overlay) },
    ) {
        val state by player.state.collectAsState()
        val recorder = if (request.stats) {
            rememberStatsRecorder(
                player,
                isFullscreen = { Fullscreen.isOn(windowState) },
                isControlsWindowVisible = { overlay.controlsVisible },
            )
        } else {
            null
        }
        LaunchedEffect(overlay.lastActivity, state.paused) {
            overlay.controlsVisible = true
            if (!state.paused) {
                delay(3_000)
                overlay.controlsVisible = false
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            MpvMediampPlayerSurface(player.mediamp, Modifier.fillMaxSize())
            PlayerControls(
                player = player,
                recorder = recorder,
                title = displayName(request.source) + " (candidate 1: texture)",
                overlay = overlay,
                isFullscreen = Fullscreen.isOn(windowState),
                onToggleFullscreen = { toggleFullscreen(windowState) },
            )
        }
    }
}
