package com.skeksik.current.spike.webwasm

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skeksik.current.spike.web.HtmlVideoEngine
import com.skeksik.current.spike.web.PlayerMenu
import com.skeksik.current.spike.web.PlayerViewModel
import com.skeksik.current.spike.web.Route
import com.skeksik.current.spike.web.SUBTITLES_OFF
import com.skeksik.current.spike.web.Track
import com.skeksik.current.spike.web.WebApp
import com.skeksik.current.spike.web.attachPlayerKeys
import com.skeksik.current.spike.web.holdViewModel

private const val DOUBLE_CLICK_MS = 300.0

@Composable
fun PlayerPage(app: WebApp, id: String) {
    val vm = rememberViewModel(id) { holdViewModel { app.graph.playerViewModel(id) } }
    val state by vm.state.collectAsState()
    DisposableEffect(vm) {
        val video = createLayerVideo("Video")
        vm.attach(HtmlVideoEngine(video, pageElement()))
        val keys = attachPlayerKeys(vm)
        onDispose {
            keys.close()
            vm.detach()
            removeLayerVideo(video)
        }
    }
    // Every click toggles play; a second one within the double-click time also toggles fullscreen (as dblclick does in the DOM).
    // detectTapGestures' onDoubleTap would delay every single tap (ADR 0010).
    val lastTap = remember { DoubleArray(1) }
    Box(
        Modifier.fillMaxSize()
            // Compose paints the canvas white where nothing is drawn: clear these pixels of the root canvas (not of an offscreen
            // layer, which would leave the white below) so the <video> behind it shows through.
            .drawBehind { drawRect(Color.Black, blendMode = BlendMode.Clear) }
            .pointerInput(vm) {
                awaitPointerEventScope {
                    while (true) {
                        if (awaitPointerEvent().type == PointerEventType.Move) vm.onActivity()
                    }
                }
            }
            .pointerInput(vm) {
                detectTapGestures(onTap = {
                    val now = nowMillis()
                    vm.togglePlay()
                    if (now - lastTap[0] < DOUBLE_CLICK_MS) vm.toggleFullscreen()
                    lastTap[0] = now
                })
            },
    ) {
        val status = state.error ?: if (state.loading || state.playback.buffering) "Loading…" else null
        status?.let {
            Text(it, color = if (state.error != null) ErrorColor else TextColor, modifier = Modifier.align(Alignment.Center))
        }
        if (state.controlsVisible) {
            Row(
                Modifier.align(Alignment.TopStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HtmlLink(app, Route.Item(id), "Back") {
                    Text("← Back", modifier = Modifier.background(SurfaceColor, RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
                }
                Text(listOfNotNull(state.subtitle, state.title).joinToString(" · "))
                state.method?.let { Text(it.label, color = Muted, fontSize = 12.sp) }
            }
            Controls(vm, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun Controls(vm: PlayerViewModel, modifier: Modifier) {
    val state by vm.state.collectAsState()
    val playback = state.playback
    Row(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FilledTonalButton(onClick = { vm.togglePlay() }) { Text(if (playback.paused) "Play" else "Pause") }
        Slider(
            value = playback.positionSeconds.toFloat(),
            onValueChange = { vm.seekTo(it.toDouble()) },
            valueRange = 0f..playback.durationSeconds.coerceAtLeast(1.0).toFloat(),
            modifier = Modifier.weight(1f).semantics { contentDescription = "Seek" },
        )
        Text("${state.positionText} / ${state.durationText}", color = Muted)
        TrackMenu(vm, PlayerMenu.Audio, "Audio", state.openMenu, state.audioTracks, state.audioIndex, withOff = false, onSelect = vm::selectAudio)
        TrackMenu(vm, PlayerMenu.Subtitles, "Subtitles", state.openMenu, state.subtitleTracks, state.subtitleIndex, withOff = true, onSelect = vm::selectSubtitle)
        FilledTonalButton(onClick = { vm.toggleFullscreen() }) { Text(if (playback.fullscreen) "Exit full screen" else "Full screen") }
    }
}

@Composable
private fun TrackMenu(
    vm: PlayerViewModel,
    menu: PlayerMenu,
    label: String,
    open: PlayerMenu?,
    tracks: List<Track>,
    selected: Int?,
    withOff: Boolean,
    onSelect: (Int) -> Unit,
) {
    Box {
        FilledTonalButton(onClick = { vm.toggleMenu(menu) }, enabled = tracks.isNotEmpty()) { Text(label) }
        DropdownMenu(expanded = open == menu, onDismissRequest = { vm.closeMenu() }) {
            val entries = (if (withOff) listOf(Track(SUBTITLES_OFF, "Off", false)) else emptyList()) + tracks
            entries.forEach { track ->
                DropdownMenuItem(
                    text = { Text((if (track.index == selected) "• " else "   ") + track.label) },
                    onClick = { onSelect(track.index) },
                )
            }
        }
    }
}
