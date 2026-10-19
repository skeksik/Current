package com.skeksik.current.spike.desktopvideo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import kotlinx.coroutines.delay
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.io.File
import javax.imageio.ImageIO
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Scripted run through everything that crashed or that the spike measures: playback start, resizing, moving,
 * maximize, fullscreen, audio and subtitle switching, seeking, the hidden controls window, pause, close.
 * Each step goes to the event trail with a screenshot; the process exits 0 on success, 2 on a failed step,
 * 4 if the whole run hangs (and a JVM crash leaves hs_err_pid*.log).
 */
@Composable
fun SelfTest(session: PlayerSession, windowState: WindowState, overlay: OverlayState, onFinished: () -> Unit) {
    val finish by rememberUpdatedState(onFinished)
    LaunchedEffect(Unit) {
        val watchdog = thread(name = "selftest-watchdog", isDaemon = true) {
            try {
                Thread.sleep(180_000)
                Diagnostics.event("[selftest] TIMEOUT: no result after 180 s")
                Runtime.getRuntime().halt(4)
            } catch (_: InterruptedException) {
            }
        }
        var stepName = "start"
        suspend fun step(name: String, block: suspend () -> Unit) {
            stepName = name
            Diagnostics.event("[selftest] step: $name")
            block()
        }
        suspend fun awaitTrue(what: String, timeoutMs: Long, condition: () -> Boolean) {
            val end = System.currentTimeMillis() + timeoutMs
            while (!condition()) {
                if (System.currentTimeMillis() > end) {
                    Diagnostics.event("[selftest] FAIL in '$stepName': timed out waiting for $what (error: ${session.error ?: session.player?.state?.value?.error})")
                    screenshot("fail")
                    exitProcess(2)
                }
                delay(50)
            }
        }
        fun player() = session.player ?: run {
            Diagnostics.event("[selftest] FAIL in '$stepName': no mpv instance")
            exitProcess(2)
        }

        step("wait for mpv") { awaitTrue("mpv instance", 15_000) { session.player != null } }
        step("wait for playback") {
            awaitTrue("playback to start", 30_000) { player().state.value.let { it.durationSeconds > 0 && it.positionSeconds > 0.5 } }
            delay(1_500)
            screenshot("01-playing")
        }
        step("resize") {
            for ((w, h) in listOf(960 to 540, 1600 to 900, 800 to 450, 1400 to 800, 1280 to 720)) {
                windowState.size = DpSize(w.dp, h.dp)
                delay(700)
            }
            screenshot("02-resized")
        }
        step("move") {
            for ((x, y) in listOf(40 to 40, 300 to 200, 120 to 80)) {
                windowState.position = WindowPosition(x.dp, y.dp)
                delay(500)
            }
        }
        step("maximize") {
            windowState.placement = WindowPlacement.Maximized
            delay(1_500)
            screenshot("03-maximized")
            windowState.placement = WindowPlacement.Floating
            delay(1_000)
        }
        step("fullscreen") {
            windowState.placement = WindowPlacement.Fullscreen
            delay(2_000)
            screenshot("04-fullscreen")
            windowState.placement = WindowPlacement.Floating
            delay(1_000)
        }
        step("audio tracks") {
            for (track in player().state.value.audioTracks) {
                player().selectAudio(track.id)
                delay(1_500)
            }
        }
        step("subtitle tracks") {
            player().selectSubtitle(null)
            delay(1_000)
            for (track in player().state.value.subtitleTracks) {
                player().selectSubtitle(track.id)
                delay(1_500)
            }
            screenshot("05-subtitles")
        }
        step("seek") {
            val target = player().state.value.durationSeconds / 2
            player().seekTo(target)
            awaitTrue("seek to finish", 10_000) { player().state.value.lastSeekMs != null }
        }
        step("hide controls window while idle") {
            overlay.hideWindowWhenIdle = true
            delay(4_500)
            screenshot("06-controls-hidden")
            overlay.hideWindowWhenIdle = false
            overlay.activity()
            delay(500)
        }
        step("pause and resume") {
            // keep-open holds a finished file paused at its end; start again from the beginning first.
            if (player().state.value.let { it.endReached || it.paused }) {
                player().seekTo(0.0)
                if (player().state.value.paused) player().togglePause()
                awaitTrue("playback after rewinding", 5_000) { player().state.value.let { !it.paused && !it.endReached } }
            }
            player().togglePause()
            awaitTrue("pause", 3_000) { player().state.value.paused }
            player().togglePause()
            awaitTrue("resume", 3_000) { !player().state.value.paused }
        }
        step("report") {
            val p = player()
            val keys = listOf("current-vo", "current-gpu-context", "hwdec-current", "frame-drop-count", "decoder-frame-drop-count", "video-target-params/gamma")
            Diagnostics.event("[selftest] stats: " + keys.joinToString { "$it=${p.getString(it)}" } + ", state=${p.state.value.copy(tracks = emptyList())}")
        }
        step("close") {
            watchdog.interrupt()
            Diagnostics.event("[selftest] PASS")
            finish()
        }
    }
}

private fun screenshot(name: String) {
    try {
        val bounds = Rectangle(Toolkit.getDefaultToolkit().screenSize)
        val image = Robot().createScreenCapture(bounds)
        val file = File(SpikePaths.outDir, "selftest-$name.png")
        ImageIO.write(image, "png", file)
        Diagnostics.event("[selftest] screenshot ${file.name}")
    } catch (e: Exception) {
        Diagnostics.event("[selftest] screenshot $name failed: $e")
    }
}
