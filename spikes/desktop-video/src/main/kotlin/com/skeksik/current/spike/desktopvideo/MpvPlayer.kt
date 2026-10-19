package com.skeksik.current.spike.desktopvideo

import com.sun.jna.Pointer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.PrintWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.thread
import kotlin.concurrent.write

data class Track(
    val id: Int,
    val type: String,
    val title: String?,
    val lang: String?,
    val codec: String?,
    val selected: Boolean,
    val external: Boolean,
    val details: String,
) {
    val label: String
        get() = listOfNotNull("#$id", lang, title, codec, details.ifBlank { null }, "external".takeIf { external }).joinToString(" · ")
}

data class PlayerState(
    val paused: Boolean = false,
    val positionSeconds: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val buffering: Boolean = false,
    val endReached: Boolean = false,
    val tracks: List<Track> = emptyList(),
    val error: String? = null,
    /** Last measured latencies, shown in the stats overlay. */
    val lastAudioSwitchMs: Long? = null,
    val lastSubtitleSwitchMs: Long? = null,
    val lastSeekMs: Long? = null,
) {
    val audioTracks: List<Track> get() = tracks.filter { it.type == "audio" }
    val subtitleTracks: List<Track> get() = tracks.filter { it.type == "sub" }
}

/**
 * One libmpv instance rendering into the native window [wid] (0: mpv opens its own window, or none with `vo=null`).
 * libmpv calls are thread-safe; events are read on a dedicated thread and folded into [state].
 */
class MpvPlayer(
    request: PlayRequest,
    wid: Long,
    extraOptions: Map<String, String> = emptyMap(),
) : SpikePlayer, AutoCloseable {
    private val mpv = Mpv.lib
    private val handle: Pointer = mpv.mpv_create() ?: error("mpv_create failed")
    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val logFile = File(SpikePaths.outDir, "mpv-$stamp.log")
    private val log = PrintWriter(logFile.bufferedWriter(), true)

    /** Guards [handle]: API calls hold the read lock, destruction takes the write lock. */
    private val lock = ReentrantReadWriteLock()
    @Volatile private var closed = false
    private lateinit var eventThread: Thread
    @Volatile private var subtitleSwitchStartedAt = 0L
    @Volatile private var seekStartedAt = 0L

    override val rejectedOptions = mutableListOf<String>()

    init {
        note("libmpv client API ${apiVersion()}, request: ${redact(request.toString())}, wid=$wid")
        Diagnostics.event("mpv: creating (wid=$wid, log ${logFile.name})")
        val options = buildMap {
            if (wid != 0L) put("wid", wid.toString())
            put("vo", request.vo)
            if (System.getProperty("os.name").startsWith("Windows") && request.gpuApi != "auto") put("gpu-api", request.gpuApi)
            put("hwdec", request.hwdec)
            put("target-colorspace-hint", if (request.hdrPassthrough) "yes" else "no")
            put("media-controls", if (request.mediaControls) "yes" else "no")
            // Our own UI draws everything: no mpv OSD, OSC or input handling.
            put("osd-level", "0")
            put("osc", "no")
            put("input-default-bindings", "no")
            put("input-vo-keyboard", "no")
            put("input-cursor", "no")
            put("cursor-autohide", "no")
            // Play on without sound when there is no usable audio device instead of failing the file.
            put("audio-fallback-to-null", "yes")
            put("keep-open", "yes")
            put("idle", "yes")
            put("terminal", "no")
            putAll(extraOptions)
        }
        options.forEach { (name, value) -> setOption(name, value) }
        // Verbose, so a crash leaves context in the log; every line is flushed as it arrives.
        check(mpv.mpv_request_log_messages(handle, "v") >= 0)
        val initResult = mpv.mpv_initialize(handle)
        check(initResult >= 0) { "mpv_initialize failed: ${mpv.mpv_error_string(initResult)}" }

        observe("pause", Mpv.FORMAT_FLAG)
        observe("time-pos", Mpv.FORMAT_DOUBLE)
        observe("duration", Mpv.FORMAT_DOUBLE)
        observe("paused-for-cache", Mpv.FORMAT_FLAG)
        observe("eof-reached", Mpv.FORMAT_FLAG)
        observe("track-list/count", Mpv.FORMAT_INT64)
        observe("aid", Mpv.FORMAT_STRING)
        observe("sid", Mpv.FORMAT_STRING)

        eventThread = thread(name = "mpv-events", isDaemon = true) { eventLoop() }
        Diagnostics.event("mpv: initialized, loading ${displayName(request.source)}")
        command("loadfile", request.source)
    }

    override fun togglePause() = command("cycle", "pause")

    override fun seekTo(seconds: Double) {
        seekStartedAt = System.nanoTime()
        command("seek", seconds.toString(), "absolute+exact")
    }

    override fun seekBy(seconds: Double) {
        seekStartedAt = System.nanoTime()
        command("seek", seconds.toString(), "relative+exact")
    }

    /**
     * Switches the audio track and measures how long until the new track's audio is playing: `current-tracks/audio/id` names
     * it and `audio-pts` has advanced twice after that, polled every 5 ms (the A/V sync right after goes to the log).
     */
    override fun selectAudio(id: Int) {
        val started = System.nanoTime()
        setProperty("aid", id.toString())
        thread(name = "audio-switch-timer", isDaemon = true) {
            var lastPts: Double? = null
            var advances = 0
            while (advances < 2) {
                if (System.nanoTime() - started > 5_000_000_000L) {
                    note("audio track switch to $id: no audio progress after 5 s")
                    return@thread
                }
                Thread.sleep(5)
                if (getString("current-tracks/audio/id") != id.toString()) continue
                val pts = getString("audio-pts")?.toDoubleOrNull() ?: continue
                if (lastPts != null && pts > lastPts) advances++
                lastPts = pts
            }
            val ms = (System.nanoTime() - started) / 1_000_000
            note("audio track switch to $id: playing after $ms ms, avsync ${getString("avsync")}")
            Diagnostics.event("audio track switch to $id: playing after $ms ms")
            _state.update { it.copy(lastAudioSwitchMs = ms) }
        }
    }

    override fun selectSubtitle(id: Int?) {
        subtitleSwitchStartedAt = System.nanoTime()
        setProperty("sid", id?.toString() ?: "no")
    }

    override fun getString(name: String): String? {
        lock.read {
            if (closed) return null
            val p = mpv.mpv_get_property_string(handle, name) ?: return null
            try {
                return p.getString(0, "UTF-8")
            } finally {
                mpv.mpv_free(p)
            }
        }
    }

    fun command(vararg args: String) {
        lock.read {
            if (closed) return
            val result = mpv.mpv_command(handle, arrayOf(*args, null))
            if (result < 0) note("command ${redact(args.joinToString(" "))} failed: ${mpv.mpv_error_string(result)}")
        }
    }

    fun setProperty(name: String, value: String) {
        lock.read {
            if (closed) return
            val result = mpv.mpv_set_property_string(handle, name, value)
            if (result < 0) note("set $name=$value failed: ${mpv.mpv_error_string(result)}")
        }
    }

    override fun note(message: String) {
        log.println("${LocalDateTime.now()} [spike] ${redact(message)}")
    }

    /** Stops playback and frees libmpv. Blocks until mpv has released the window, so call it before disposing [wid]. */
    override fun close() {
        if (closed) return
        Diagnostics.event("mpv: closing")
        // "quit" shuts the core down; the event thread sees MPV_EVENT_SHUTDOWN and returns.
        command("quit")
        eventThread.join(5_000)
        if (eventThread.isAlive) Diagnostics.event("mpv: event thread still running after 5 s")
        lock.write {
            closed = true
            mpv.mpv_terminate_destroy(handle)
        }
        Diagnostics.event("mpv: closed")
        log.close()
    }

    private fun apiVersion(): String {
        val v = mpv.mpv_client_api_version().toLong()
        return "${v shr 16}.${v and 0xffff}"
    }

    private fun setOption(name: String, value: String) {
        val result = mpv.mpv_set_option_string(handle, name, value)
        if (result < 0) {
            rejectedOptions += "$name=$value (${mpv.mpv_error_string(result)})"
            note("option $name=$value rejected: ${mpv.mpv_error_string(result)}")
        }
    }

    private fun observe(name: String, format: Int) {
        check(mpv.mpv_observe_property(handle, 0, name, format) >= 0) { "observe $name failed" }
    }

    private fun eventLoop() {
        while (true) {
            val event = MpvEvent(mpv.mpv_wait_event(handle, -1.0))
            when (event.id) {
                Mpv.EVENT_SHUTDOWN -> return
                Mpv.EVENT_LOG_MESSAGE -> log.println(redact("${LocalDateTime.now()} [${event.logPrefix}] ${event.logLevel}: ${event.logText.trimEnd()}"))
                Mpv.EVENT_PROPERTY_CHANGE -> onPropertyChange(event)
                Mpv.EVENT_FILE_LOADED -> {
                    note("file loaded")
                    Diagnostics.event("mpv: file loaded")
                    _state.update { it.copy(error = null, endReached = false) }
                }
                Mpv.EVENT_END_FILE -> if (event.endFileReason == Mpv.END_FILE_REASON_ERROR) {
                    val message = "Playback failed: ${mpv.mpv_error_string(event.endFileError)}"
                    note(message)
                    Diagnostics.event("mpv: $message")
                    _state.update { it.copy(error = message) }
                }
                Mpv.EVENT_PLAYBACK_RESTART -> seekStartedAt.takeIf { it != 0L }?.let { started ->
                    seekStartedAt = 0L
                    val ms = (System.nanoTime() - started) / 1_000_000
                    note("seek: playback restarted after $ms ms")
                    _state.update { it.copy(lastSeekMs = ms) }
                }
                Mpv.EVENT_VIDEO_RECONFIG -> {
                    val message = "video reconfig: vo=${getString("current-vo")} hwdec=${getString("hwdec-current")} " +
                        "params=${getString("video-params/pixelformat")}/${getString("video-params/primaries")}/${getString("video-params/gamma")} " +
                        "target=${getString("video-target-params/primaries")}/${getString("video-target-params/gamma")}"
                    note(message)
                    Diagnostics.event("mpv: $message")
                }
            }
        }
    }

    private fun onPropertyChange(event: MpvEvent) {
        when (event.propertyName) {
            "pause" -> event.propertyFlag?.let { v -> _state.update { it.copy(paused = v) } }
            "time-pos" -> _state.update { it.copy(positionSeconds = event.propertyDouble ?: 0.0) }
            "duration" -> _state.update { it.copy(durationSeconds = event.propertyDouble ?: 0.0) }
            "paused-for-cache" -> event.propertyFlag?.let { v -> _state.update { it.copy(buffering = v) } }
            "eof-reached" -> event.propertyFlag?.let { v -> _state.update { it.copy(endReached = v) } }
            "track-list/count", "aid" -> _state.update { it.copy(tracks = readTracks()) }
            "sid" -> {
                subtitleSwitchStartedAt.takeIf { it != 0L }?.let { started ->
                    subtitleSwitchStartedAt = 0L
                    val ms = (System.nanoTime() - started) / 1_000_000
                    note("subtitle track switch: sid changed after $ms ms")
                    _state.update { it.copy(lastSubtitleSwitchMs = ms) }
                }
                _state.update { it.copy(tracks = readTracks()) }
            }
        }
    }

    private fun readTracks(): List<Track> = readTracks(::getString)
}
