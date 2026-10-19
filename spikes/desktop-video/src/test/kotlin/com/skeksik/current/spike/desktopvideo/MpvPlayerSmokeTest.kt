package com.skeksik.current.spike.desktopvideo

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Headless check of the libmpv binding (no window, no audio device). Runs only when -Dspike.testMedia points at a file
 * with one video, two audio and one subtitle track, and a libmpv can be loaded; otherwise it does nothing.
 */
class MpvPlayerSmokeTest {
    @Test
    fun playsSwitchesTracksAndSeeks() {
        val media = System.getProperty("spike.testMedia")?.let(::File)?.takeIf { it.isFile } ?: return
        MpvPlayer(PlayRequest(media.absolutePath), wid = 0, extraOptions = mapOf("vo" to "null", "ao" to "null", "pause" to "yes")).use { player ->
            awaitTrue { player.state.value.durationSeconds > 0 && player.state.value.tracks.size == 4 }
            val state = player.state.value
            assertEquals(listOf("video", "audio", "audio", "sub"), state.tracks.map { it.type })
            assertEquals("deu", state.audioTracks[1].lang)

            player.togglePause()
            awaitTrue { !player.state.value.paused }

            player.selectAudio(state.audioTracks[1].id)
            awaitTrue { player.state.value.audioTracks[1].selected }
            awaitTrue { player.state.value.lastAudioSwitchMs != null }

            player.selectSubtitle(null)
            awaitTrue { player.state.value.subtitleTracks.none { it.selected } }
            player.selectSubtitle(state.subtitleTracks[0].id)
            awaitTrue { player.state.value.subtitleTracks[0].selected }

            player.seekTo(6.0)
            awaitTrue { player.state.value.lastSeekMs != null && player.state.value.positionSeconds >= 6.0 }

            assertNotNull(player.getString("frame-drop-count") ?: player.getString("decoder-frame-drop-count"))
            assertTrue(player.getString("video-params/w") == "1280")
            println("rejected options: ${player.rejectedOptions}; state: ${player.state.value}")

            val recorder = StatsRecorder(player)
            runBlocking { withTimeoutOrNull(2_500) { recorder.run(isFullscreen = { false }, isControlsWindowVisible = { true }) } }
            val lines = recorder.csvFile.readLines()
            assertTrue(lines.size >= 2, "a header and at least one sample")
            assertEquals(lines[0].split(",").size, lines[1].split(",").size)
            println("build: ${recorder.buildInfo}")
            println("empty columns: " + lines[0].split(",").zip(lines[1].split(",")).filter { it.second.isEmpty() }.map { it.first })
        }
    }

    private fun awaitTrue(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(20)
        }
    }
}
