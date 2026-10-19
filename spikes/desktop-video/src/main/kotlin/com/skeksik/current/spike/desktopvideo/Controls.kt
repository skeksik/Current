package com.skeksik.current.spike.desktopvideo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState

/**
 * Our own controls, drawn in the transparent window above the video: play/pause, seek bar, audio and subtitle menus,
 * fullscreen, and the measurement overlay.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlayerControls(
    player: SpikePlayer,
    recorder: StatsRecorder?,
    title: String,
    overlay: OverlayState,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
) {
    val state by player.state.collectAsState()
    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(
            Modifier
                .fillMaxSize()
                // Windows 11 rounds a floating window's corners (8 px at 100 %); a square overlay would stick out past them.
                .clip(if (isFullscreen) RectangleShape else RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                // Nearly invisible fill: keeps every pixel of the transparent window hit-testable, so clicks and
                // mouse moves over the video reach us instead of falling through to the video window.
                .background(Color.Black.copy(alpha = 1f / 255f))
                .pointerHoverIcon(if (overlay.controlsVisible) PointerIcon.Default else PointerIcon(blankCursor))
                .onPointerEvent(PointerEventType.Move) { overlay.activity() }
                .pointerInput(player) {
                    var lastPress = 0L
                    awaitEachGesture {
                        awaitFirstDown()
                        // Released here and not consumed by a child (bottom bar, menus): a click on the video itself.
                        waitForUpOrCancellation() ?: return@awaitEachGesture
                        val now = System.currentTimeMillis()
                        overlay.activity()
                        if (now - lastPress < viewConfiguration.doubleTapTimeoutMillis) {
                            lastPress = 0L
                            player.togglePause()
                            onToggleFullscreen()
                        } else {
                            lastPress = now
                            player.togglePause()
                        }
                    }
                },
        ) {
            AnimatedVisibility(overlay.controlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopStart)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                        .padding(16.dp),
                )
            }
            if (overlay.showStats && recorder != null) {
                StatsPanel(recorder, Modifier.align(Alignment.TopEnd).padding(top = 48.dp, end = 12.dp))
            }
            state.error?.let { error ->
                Text(error, color = Color.White, modifier = Modifier.align(Alignment.Center).background(Color(0xCCB00020)).padding(16.dp))
            }
            if (state.buffering) {
                Text("Buffering…", color = Color.White, modifier = Modifier.align(Alignment.Center))
            }
            AnimatedVisibility(overlay.controlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                BottomBar(player, state, overlay, isFullscreen, onToggleFullscreen)
            }
        }
    }
}

@Composable
private fun BottomBar(
    player: SpikePlayer,
    state: PlayerState,
    overlay: OverlayState,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var pendingSeek by remember { mutableStateOf<Double?>(null) }
    val duration = state.durationSeconds
    pendingSeek?.let { target -> if (kotlin.math.abs(state.positionSeconds - target) < 1.5) pendingSeek = null }
    val shownPosition = pendingSeek ?: state.positionSeconds
    val fraction = if (dragging) dragFraction else if (duration > 0) (shownPosition / duration).toFloat().coerceIn(0f, 1f) else 0f

    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
            // Taps on the bar must not reach the play/pause tap area behind it.
            .pointerInput(Unit) { detectTapGestures { overlay.activity() } }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Slider(
            value = fraction,
            enabled = duration > 0,
            onValueChange = {
                overlay.activity()
                dragging = true
                dragFraction = it
            },
            onValueChangeFinished = {
                pendingSeek = dragFraction * duration
                player.seekTo(dragFraction * duration)
                dragging = false
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { player.togglePause() }) { Text(if (state.paused) "▶ Play" else "❚❚ Pause") }
            TextButton(onClick = { player.seekBy(-10.0) }) { Text("−10 s") }
            TextButton(onClick = { player.seekBy(30.0) }) { Text("+30 s") }
            Text(
                "${formatTime(if (dragging) dragFraction * duration else state.positionSeconds)} / ${formatTime(duration)}",
                color = Color.White,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Box(Modifier.weight(1f))
            TrackMenu(
                label = "Audio",
                tracks = state.audioTracks,
                allowOff = false,
                onSelect = { id -> id?.let(player::selectAudio) },
                onOpen = overlay::activity,
            )
            TrackMenu(
                label = "Subtitles",
                tracks = state.subtitleTracks,
                allowOff = true,
                onSelect = player::selectSubtitle,
                onOpen = overlay::activity,
            )
            TextButton(onClick = { overlay.showStats = !overlay.showStats }) { Text(if (overlay.showStats) "Hide stats" else "Stats") }
            TextButton(onClick = onToggleFullscreen) { Text(if (isFullscreen) "Exit fullscreen" else "Fullscreen") }
        }
    }
}

@Composable
private fun TrackMenu(
    label: String,
    tracks: List<Track>,
    allowOff: Boolean,
    onSelect: (Int?) -> Unit,
    onOpen: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            enabled = tracks.isNotEmpty(),
            onClick = {
                onOpen()
                expanded = true
            },
        ) { Text("$label (${tracks.size})") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (allowOff) {
                DropdownMenuItem(
                    text = { Text((if (tracks.none { it.selected }) "✓ " else "   ") + "Off") },
                    onClick = {
                        onSelect(null)
                        expanded = false
                    },
                )
            }
            tracks.forEach { track ->
                DropdownMenuItem(
                    text = { Text((if (track.selected) "✓ " else "   ") + track.label, modifier = Modifier.widthIn(max = 520.dp)) },
                    onClick = {
                        onSelect(track.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Keyboard shortcuts, handled by both the video window and the controls window (whichever has focus). */
fun handleKey(event: KeyEvent, player: SpikePlayer, windowState: WindowState, overlay: OverlayState): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    overlay.activity()
    val state = player.state.value
    when (event.key) {
        Key.Spacebar, Key.K -> player.togglePause()
        Key.DirectionLeft -> player.seekBy(-5.0)
        Key.DirectionRight -> player.seekBy(5.0)
        Key.F -> toggleFullscreen(windowState)
        Key.Escape -> if (Fullscreen.isOn(windowState)) Fullscreen.set(windowState, false) else return false
        Key.A -> cycle(state.audioTracks)?.let(player::selectAudio)
        Key.S -> {
            val subs = state.subtitleTracks
            val current = subs.indexOfFirst { it.selected }
            // Off -> first -> ... -> last -> off
            player.selectSubtitle(if (current + 1 < subs.size) subs[current + 1].id else null)
        }
        Key.I -> overlay.showStats = !overlay.showStats
        Key.H -> overlay.hideWindowWhenIdle = !overlay.hideWindowWhenIdle
        else -> return false
    }
    return true
}

private fun cycle(tracks: List<Track>): Int? {
    if (tracks.isEmpty()) return null
    val current = tracks.indexOfFirst { it.selected }
    return tracks[(current + 1) % tracks.size].id
}

fun formatTime(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
