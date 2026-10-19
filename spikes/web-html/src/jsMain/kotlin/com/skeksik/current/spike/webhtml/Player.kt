package com.skeksik.current.spike.webhtml

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.skeksik.current.spike.web.HtmlVideoEngine
import com.skeksik.current.spike.web.PlayerMenu
import com.skeksik.current.spike.web.PlayerViewModel
import com.skeksik.current.spike.web.Route
import com.skeksik.current.spike.web.SUBTITLES_OFF
import com.skeksik.current.spike.web.Track
import com.skeksik.current.spike.web.WebApp
import com.skeksik.current.spike.web.attachPlayerKeys
import com.skeksik.current.spike.web.holdViewModel
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.max
import org.jetbrains.compose.web.attributes.min
import org.jetbrains.compose.web.attributes.step
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Video
import org.w3c.dom.HTMLElement

@Composable
fun PlayerPage(app: WebApp, id: String) {
    val vm = rememberViewModel(id) { holdViewModel { app.graph.playerViewModel(id) } }
    val state by vm.state.collectAsState()
    DisposableEffect(vm) {
        val keys = attachPlayerKeys(vm)
        onDispose { keys.close() }
    }
    Div({
        classes("player")
        if (!state.controlsVisible) classes("idle")
        onMouseMove { vm.onActivity() }
    }) {
        Video({
            // Compose HTML owns this element's attributes and drops ones set from outside on recomposition, so the engine's
            // crossorigin (needed for cross-origin subtitle tracks) is declared here too.
            attr("crossorigin", "anonymous")
            attr("playsinline", "")
            attr("aria-label", state.title)
            onClick { vm.togglePlay() }
            onDoubleClick { vm.toggleFullscreen() }
            ref { video ->
                vm.attach(HtmlVideoEngine(video, video.parentElement as HTMLElement))
                onDispose { vm.detach() }
            }
        })
        val status = state.error ?: if (state.loading || state.playback.buffering) "Loading…" else null
        status?.let { Div({ classes("status"); if (state.error != null) classes("error"); attr("role", "status") }) { Text(it) } }
        if (state.controlsVisible) {
            Div({ classes("top") }) {
                AppLink(app, Route.Item(id), { classes("button") }) { Text("← Back") }
                Span { Text(listOfNotNull(state.subtitle, state.title).joinToString(" · ")) }
                state.method?.let { Span({ classes("badge") }) { Text(it.label) } }
            }
            Controls(vm)
        }
    }
}

@Composable
private fun Controls(vm: PlayerViewModel) {
    val state by vm.state.collectAsState()
    val playback = state.playback
    Div({ classes("controls") }) {
        Button({ attr("aria-label", if (playback.paused) "Play" else "Pause"); onClick { vm.togglePlay() } }) {
            Text(if (playback.paused) "▶" else "❚❚")
        }
        Input(InputType.Range) {
            classes("seek")
            attr("aria-label", "Seek")
            min("0"); max(playback.durationSeconds.coerceAtLeast(0.0).toString()); step(1)
            value(playback.positionSeconds)
            attr("aria-valuetext", "${state.positionText} of ${state.durationText}")
            onInput { e -> e.value?.toDouble()?.let(vm::seekTo) }
        }
        Span({ classes("time") }) { Text("${state.positionText} / ${state.durationText}") }
        TrackMenu(vm, PlayerMenu.Audio, "Audio", state.openMenu, state.audioTracks, state.audioIndex, withOff = false, onSelect = vm::selectAudio)
        TrackMenu(vm, PlayerMenu.Subtitles, "Subtitles", state.openMenu, state.subtitleTracks, state.subtitleIndex, withOff = true, onSelect = vm::selectSubtitle)
        Button({ attr("aria-label", "Fullscreen"); onClick { vm.toggleFullscreen() } }) { Text(if (playback.fullscreen) "⤡" else "⛶") }
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
    Div({ classes("menu-anchor") }) {
        Button({
            attr("aria-haspopup", "menu")
            attr("aria-expanded", (open == menu).toString())
            if (tracks.isEmpty()) attr("disabled", "")
            onClick { vm.toggleMenu(menu) }
        }) { Text(label) }
        if (open == menu) {
            Div({ classes("menu"); attr("role", "menu") }) {
                val entries = (if (withOff) listOf(Track(SUBTITLES_OFF, "Off", false)) else emptyList()) + tracks
                entries.forEach { track ->
                    Button({
                        attr("role", "menuitemradio")
                        attr("aria-checked", (track.index == selected).toString())
                        onClick { onSelect(track.index) }
                    }) { Text(track.label) }
                }
            }
        }
    }
}
