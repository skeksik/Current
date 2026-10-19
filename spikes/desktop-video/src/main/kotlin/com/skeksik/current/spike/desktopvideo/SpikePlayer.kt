package com.skeksik.current.spike.desktopvideo

import kotlinx.coroutines.flow.StateFlow

/**
 * What the controls, the stats recorder and the self-test need from a player, so both candidates share them:
 * [MpvPlayer] (candidate 2, libmpv in a native window) and [TexturePlayer] (candidate 1, libmpv drawn into Compose).
 */
interface SpikePlayer {
    val state: StateFlow<PlayerState>

    /** Options mpv rejected or that do not exist in this libmpv build, reported in the stats overlay. */
    val rejectedOptions: List<String>

    fun togglePause()
    fun seekTo(seconds: Double)
    fun seekBy(seconds: Double)
    fun selectAudio(id: Int)

    /** [id] null turns subtitles off. */
    fun selectSubtitle(id: Int?)

    /** An mpv property as a string, or null when it is unavailable or the player is closed. */
    fun getString(name: String): String?

    /** Writes a line to this player's log. */
    fun note(message: String)
}

/** Reads mpv's `track-list` through [getString]. */
fun readTracks(getString: (String) -> String?): List<Track> {
    val count = getString("track-list/count")?.toIntOrNull() ?: return emptyList()
    return (0 until count).mapNotNull { i ->
        fun p(name: String) = getString("track-list/$i/$name")
        val id = p("id")?.toIntOrNull() ?: return@mapNotNull null
        val type = p("type") ?: return@mapNotNull null
        val details = when (type) {
            "video" -> listOfNotNull(p("demux-w")?.let { w -> "${w}x${p("demux-h")}" }, p("demux-fps")?.let { "$it fps" }).joinToString(" ")
            "audio" -> listOfNotNull(p("demux-channel-count")?.let { "$it ch" }, p("demux-samplerate")?.let { "$it Hz" }).joinToString(" ")
            else -> ""
        }
        Track(
            id = id,
            type = type,
            title = p("title"),
            lang = p("lang"),
            codec = p("codec"),
            selected = p("selected") == "yes",
            external = p("external") == "yes",
            details = details,
        )
    }
}
