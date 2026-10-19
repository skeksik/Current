package com.skeksik.current.spike.desktopvideo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.PrintWriter
import java.lang.management.ManagementFactory
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

/** mpv properties sampled every second, in CSV column order. */
private val sampledProperties = listOf(
    "time-pos", "current-vo", "current-gpu-context", "hwdec-current", "video-codec",
    "video-params/w", "video-params/h", "video-params/pixelformat", "video-params/hw-pixelformat",
    "video-params/primaries", "video-params/gamma", "video-params/max-luma", "video-params/max-cll",
    "video-target-params/primaries", "video-target-params/gamma", "video-target-params/max-luma",
    "container-fps", "estimated-vf-fps", "display-fps",
    "frame-drop-count", "decoder-frame-drop-count", "vo-delayed-frame-count", "mistimed-frame-count", "avsync",
    "audio-codec-name", "audio-params/channel-count", "audio-params/samplerate", "current-ao",
    "demuxer-cache-duration", "cache-speed", "osd-width", "osd-height", "sid", "sub-start", "sub-end",
)

private val extraColumns = listOf(
    "process-cpu-pct", "system-cpu-pct", "gpu-3d-pct-self", "gpu-decode-pct-self", "gpu-3d-pct-max-engine", "gpu-decode-pct-max-engine",
    "last-seek-ms", "last-audio-switch-ms", "last-sub-switch-ms", "fullscreen", "controls-window-visible",
)

/**
 * Samples playback and system load once a second into [latest] and a CSV file next to the mpv log.
 * CPU is the whole JVM process (libmpv runs inside it). GPU load comes from Windows' "GPU Engine" counters via
 * typeperf: an approximation of Task Manager, which stays the reference.
 */
class StatsRecorder(private val player: SpikePlayer) {
    private val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val csvFile = File(SpikePaths.outDir, "stats-$stamp.csv")
    private val _latest = MutableStateFlow<Map<String, String>>(emptyMap())
    val latest: StateFlow<Map<String, String>> = _latest
    private val gpu = GpuCounters()

    /** Static facts about the libmpv build, read once. */
    val buildInfo: Map<String, String> by lazy {
        val configuration = player.getString("mpv-configuration").orEmpty()
        mapOf(
            "mpv" to (player.getString("mpv-version") ?: "?"),
            "ffmpeg" to (player.getString("ffmpeg-version") ?: "?"),
            "gpl" to (Regex("-Dgpl=(\\w+)").find(configuration)?.groupValues?.get(1) ?: "?"),
            "win32-smtc" to (Regex("-Dwin32-smtc=(\\w+)").find(configuration)?.groupValues?.get(1) ?: "?"),
            "rejected options" to player.rejectedOptions.joinToString().ifBlank { "none" },
        )
    }

    suspend fun run(isFullscreen: () -> Boolean, isControlsWindowVisible: () -> Boolean) = withContext(Dispatchers.IO) {
        val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
        PrintWriter(csvFile.bufferedWriter(), true).use { csv ->
            csv.println((listOf("wallclock") + sampledProperties + extraColumns).joinToString(","))
            player.note("stats: ${csvFile.absolutePath}; build: $buildInfo")
            try {
                while (isActive) {
                    val values = sampledProperties.associateWith { player.getString(it).orEmpty() }
                    if (!gpu.started && values["hwdec-current"].orEmpty().isNotEmpty() && values["time-pos"].orEmpty().isNotEmpty()) {
                        // Start after decoding began, so the decode engine's counter instance for this process exists.
                        gpu.start()
                    }
                    val load = gpu.latest
                    val state = player.state.value
                    val extras = mapOf(
                        "process-cpu-pct" to pct(os.processCpuLoad),
                        "system-cpu-pct" to pct(os.cpuLoad),
                        "gpu-3d-pct-self" to load?.self3d.fmt(),
                        "gpu-decode-pct-self" to load?.selfDecode.fmt(),
                        "gpu-3d-pct-max-engine" to load?.max3d.fmt(),
                        "gpu-decode-pct-max-engine" to load?.maxDecode.fmt(),
                        "last-seek-ms" to state.lastSeekMs?.toString().orEmpty(),
                        "last-audio-switch-ms" to state.lastAudioSwitchMs?.toString().orEmpty(),
                        "last-sub-switch-ms" to state.lastSubtitleSwitchMs?.toString().orEmpty(),
                        "fullscreen" to isFullscreen().toString(),
                        "controls-window-visible" to isControlsWindowVisible().toString(),
                    )
                    val row = values + extras
                    _latest.value = row + mapOf("gpu-status" to gpu.status)
                    if (values["time-pos"].orEmpty().isNotEmpty()) {
                        csv.println((listOf(LocalDateTime.now().toString()) + (sampledProperties + extraColumns).map { csvCell(row[it].orEmpty()) }).joinToString(","))
                    }
                    delay(1_000)
                }
            } finally {
                gpu.stop()
            }
        }
    }

    private fun pct(fraction: Double) = if (fraction < 0) "" else "%.1f".format(fraction * 100)
    private fun Double?.fmt() = this?.let { "%.1f".format(it) }.orEmpty()
    private fun csvCell(value: String) = if (value.contains(',') || value.contains('"')) "\"${value.replace("\"", "\"\"")}\"" else value
}

internal data class GpuLoad(val self3d: Double, val selfDecode: Double, val max3d: Double, val maxDecode: Double)

/** Reads `\GPU Engine(*)\Utilization Percentage` through a long-running `typeperf` (Windows only, English counter names). */
internal class GpuCounters {
    @Volatile var latest: GpuLoad? = null
    @Volatile var status: String = "GPU counters not started"
    @Volatile var started = false
    private var process: Process? = null

    fun start() {
        started = true
        if (!System.getProperty("os.name").startsWith("Windows")) {
            status = "GPU counters: Windows only"
            return
        }
        val pid = ProcessHandle.current().pid()
        val proc = try {
            ProcessBuilder(
                "typeperf", "-si", "1",
                "\\GPU Engine(*engtype_3D)\\Utilization Percentage",
                "\\GPU Engine(*engtype_VideoDecode)\\Utilization Percentage",
            ).redirectErrorStream(true).start()
        } catch (e: Exception) {
            status = "GPU counters unavailable: ${e.message}"
            return
        }
        process = proc
        status = "GPU counters starting"
        thread(name = "typeperf", isDaemon = true) {
            var columns: List<String>? = null
            proc.inputStream.bufferedReader().forEachLine { line ->
                val cells = parseCsvLine(line)
                when {
                    cells.size < 2 -> if (line.isNotBlank() && columns == null) status = "typeperf: ${line.trim()}"
                    columns == null && cells[0].contains("PDH-CSV") -> columns = cells
                    columns != null -> {
                        latest = summarize(columns!!, cells, pid)
                        status = "GPU counters OK"
                    }
                }
            }
        }
    }

    fun stop() {
        process?.destroy()
    }

    internal fun summarize(columns: List<String>, values: List<String>, pid: Long): GpuLoad {
        var self3d = 0.0
        var selfDecode = 0.0
        val perEngine = mutableMapOf<String, Double>()
        for (i in 1 until minOf(columns.size, values.size)) {
            val value = values[i].trim().toDoubleOrNull() ?: continue
            val instance = Regex("""GPU Engine\((.*)\)""").find(columns[i])?.groupValues?.get(1) ?: continue
            val is3d = instance.endsWith("engtype_3D")
            if (instance.startsWith("pid_${pid}_")) {
                if (is3d) self3d += value else selfDecode += value
            }
            // Same physical engine across processes: pid_X_luid_A_B_phys_N_eng_M_engtype_T -> luid_A_B_phys_N_eng_M_engtype_T
            val engine = instance.substringAfter("_luid_", instance)
            perEngine[engine] = (perEngine[engine] ?: 0.0) + value
        }
        val max3d = perEngine.filterKeys { it.endsWith("engtype_3D") }.values.maxOrNull() ?: 0.0
        val maxDecode = perEngine.filterKeys { it.endsWith("engtype_VideoDecode") }.values.maxOrNull() ?: 0.0
        return GpuLoad(self3d, selfDecode, minOf(max3d, 100.0), minOf(maxDecode, 100.0))
    }

    internal fun parseCsvLine(line: String): List<String> =
        Regex("\"((?:[^\"]|\"\")*)\"").findAll(line).map { it.groupValues[1].replace("\"\"", "\"") }.toList()
}

private val panelRows = listOf(
    "vo" to listOf("current-vo", "current-gpu-context"),
    "hwdec" to listOf("hwdec-current"),
    "video" to listOf("video-codec", "video-params/w", "video-params/h", "video-params/pixelformat", "video-params/hw-pixelformat"),
    "source" to listOf("video-params/primaries", "video-params/gamma", "video-params/max-luma", "video-params/max-cll"),
    "output" to listOf("video-target-params/primaries", "video-target-params/gamma", "video-target-params/max-luma"),
    "fps (file/est/display)" to listOf("container-fps", "estimated-vf-fps", "display-fps"),
    "drops (vo/dec)" to listOf("frame-drop-count", "decoder-frame-drop-count"),
    "delayed/mistimed" to listOf("vo-delayed-frame-count", "mistimed-frame-count"),
    "avsync" to listOf("avsync"),
    "audio" to listOf("audio-codec-name", "audio-params/channel-count", "audio-params/samplerate", "current-ao"),
    "cache s / speed" to listOf("demuxer-cache-duration", "cache-speed"),
    "window" to listOf("osd-width", "osd-height"),
    "cpu % proc/sys" to listOf("process-cpu-pct", "system-cpu-pct"),
    "gpu % self 3d/dec" to listOf("gpu-3d-pct-self", "gpu-decode-pct-self"),
    "gpu % engine 3d/dec" to listOf("gpu-3d-pct-max-engine", "gpu-decode-pct-max-engine"),
    "gpu counters" to listOf("gpu-status"),
    "last ms seek/aud/sub" to listOf("last-seek-ms", "last-audio-switch-ms", "last-sub-switch-ms"),
)

@Composable
fun StatsPanel(recorder: StatsRecorder, modifier: Modifier = Modifier) {
    val latest by recorder.latest.collectAsState()
    val style = TextStyle(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    Column(modifier.widthIn(max = 560.dp).background(Color.Black.copy(alpha = 0.6f)).padding(10.dp)) {
        recorder.buildInfo.forEach { (k, v) -> Text("%-22s %s".format(k, v), style = style, maxLines = 1) }
        panelRows.forEach { (label, keys) ->
            Text("%-22s %s".format(label, keys.joinToString(" / ") { latest[it].orEmpty().ifBlank { "–" } }), style = style, maxLines = 1)
        }
        Text("csv: ${recorder.csvFile.name}   keys: space k ← → f esc a s i h", style = style)
    }
}

@Composable
fun rememberStatsRecorder(player: SpikePlayer, isFullscreen: () -> Boolean, isControlsWindowVisible: () -> Boolean): StatsRecorder {
    val recorder = remember(player) { StatsRecorder(player) }
    LaunchedEffect(recorder) { recorder.run(isFullscreen, isControlsWindowVisible) }
    return recorder
}
