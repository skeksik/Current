package com.skeksik.current.spike.desktopvideo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

private val engineChoices = listOf("window", "texture")
private val hwdecChoices = listOf("auto-safe", "d3d11va", "d3d11va-copy", "no")
private val voChoices = listOf("gpu-next", "gpu")
private val gpuApiChoices = listOf("d3d11", "opengl", "auto")

@Composable
fun LauncherWindow(initial: PlayRequest?, onPlay: (PlayRequest) -> Unit, onCloseRequest: () -> Unit) {
    Window(
        onCloseRequest = onCloseRequest,
        title = "Desktop video spike",
        state = rememberWindowState(width = 900.dp, height = 720.dp),
    ) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                var source by remember { mutableStateOf(initial?.source.orEmpty()) }
                var engine by remember { mutableStateOf(initial?.engine ?: "window") }
                var hwdec by remember { mutableStateOf(initial?.hwdec ?: "auto-safe") }
                var vo by remember { mutableStateOf(initial?.vo ?: "gpu-next") }
                var hdr by remember { mutableStateOf(initial?.hdrPassthrough ?: true) }
                var mediaControls by remember { mutableStateOf(initial?.mediaControls ?: true) }
                var extraOptions by remember { mutableStateOf(initial?.extraOptions.orEmpty()) }
                var gpuApi by remember { mutableStateOf(initial?.gpuApi ?: "d3d11") }
                var embed by remember { mutableStateOf(initial?.embed ?: true) }
                var controlsWindow by remember { mutableStateOf(initial?.controlsWindow ?: true) }
                var stats by remember { mutableStateOf(initial?.stats ?: true) }
                var selfTest by remember { mutableStateOf(false) }
                val isUrl = source.startsWith("http://") || source.startsWith("https://")

                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Desktop video spike: libmpv with our own Compose controls", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = source,
                            onValueChange = { source = it.trim() },
                            label = { Text("Local file or Jellyfin direct-play URL") },
                            singleLine = true,
                            // The URL carries an API key: keep it off screen recordings.
                            visualTransformation = if (isUrl) PasswordVisualTransformation() else VisualTransformation.None,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = { chooseFile(window)?.let { source = it } }) { Text("Browse…") }
                    }
                    if (isUrl) Text("URL: ${redact(source)}", style = MaterialTheme.typography.bodySmall)

                    ChoiceRow("engine", engineChoices, engine) { engine = it }
                    Text(
                        "window: candidate 2, libmpv in a native window, controls in a transparent window (all options below). " +
                            "texture: candidate 1, MediaMP draws libmpv into Compose (SDR only; uses hwdec and extra options, ignores the rest).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    ChoiceRow("hwdec", hwdecChoices, hwdec) { hwdec = it }
                    ChoiceRow("vo", voChoices, vo) { vo = it }
                    ChoiceRow("gpu-api", gpuApiChoices, gpuApi) { gpuApi = it }
                    CheckRow("HDR passthrough (target-colorspace-hint=yes)", hdr) { hdr = it }
                    CheckRow("Windows media controls (media-controls=yes)", mediaControls) { mediaControls = it }
                    Text("Isolation switches (to find what crashes)", style = MaterialTheme.typography.labelLarge)
                    Row {
                        CheckRow("Embed video in our window (wid)", embed) { embed = it }
                        CheckRow("Controls window", controlsWindow) { controlsWindow = it }
                        CheckRow("Stats", stats) { stats = it }
                        CheckRow("Self-test, then exit", selfTest) { selfTest = it }
                    }
                    OutlinedTextField(
                        value = extraOptions,
                        onValueChange = { extraOptions = it },
                        label = { Text("Extra mpv options, e.g. video-sync=display-resample, interpolation=yes") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Text("libmpv from: ${SpikePaths.mpvDir.absolutePath}", style = MaterialTheme.typography.bodySmall)
                    Text("Logs and stats go to: ${SpikePaths.outDir.absolutePath}", style = MaterialTheme.typography.bodySmall)

                    Button(
                        enabled = source.isNotBlank(),
                        onClick = {
                            onPlay(
                                PlayRequest(
                                    source = source,
                                    engine = engine,
                                    hwdec = hwdec,
                                    vo = vo,
                                    gpuApi = gpuApi,
                                    hdrPassthrough = hdr,
                                    mediaControls = mediaControls,
                                    embed = embed,
                                    controlsWindow = controlsWindow,
                                    stats = stats,
                                    selfTest = selfTest,
                                    extraOptions = extraOptions,
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Play") }
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(label: String, choices: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.padding(end = 8.dp))
        choices.forEach { choice ->
            FilterChip(selected = choice == selected, onClick = { onSelect(choice) }, label = { Text(choice) })
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

private fun chooseFile(owner: Frame): String? {
    val dialog = FileDialog(owner, "Choose a video", FileDialog.LOAD)
    dialog.isVisible = true
    val file = dialog.file ?: return null
    return File(dialog.directory, file).absolutePath
}
