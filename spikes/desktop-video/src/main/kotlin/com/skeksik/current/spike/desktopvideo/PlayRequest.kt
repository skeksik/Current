package com.skeksik.current.spike.desktopvideo

import java.io.File

/** What to play and how to set up mpv for it. */
data class PlayRequest(
    /** A local path or an http(s) URL (for example a Jellyfin direct-play URL carrying `ApiKey=`). */
    val source: String,
    /** `window`: candidate 2 (libmpv in a native window, controls in a transparent window). `texture`: candidate 1 (MediaMP). */
    val engine: String = "window",
    val hwdec: String = "auto-safe",
    val vo: String = "gpu-next",
    /** `d3d11`, `opengl` or `auto` (mpv picks). */
    val gpuApi: String = "d3d11",
    /** Lets mpv switch the swap chain to the source's colour space (HDR10 output when Windows HDR is on). */
    val hdrPassthrough: Boolean = true,
    /** Turns on libmpv's Windows media controls (needs a libmpv built with win32-smtc). */
    val mediaControls: Boolean = true,
    /** Isolation switches for crash hunting: render into our canvas (`wid`) or into mpv's own window. */
    val embed: Boolean = true,
    /** Isolation switch: show the transparent controls window. */
    val controlsWindow: Boolean = true,
    /** Isolation switch: sample stats every second (overlay and CSV). */
    val stats: Boolean = true,
    /** `borderless` (default), `compose` or `compose-fix`; see [Fullscreen]. */
    val fullscreen: String = "borderless",
    /** Run the scripted self-test (resize, move, fullscreen, tracks, seek, close), then exit. */
    val selfTest: Boolean = false,
    /** More mpv options as `name=value`, comma-separated; they override the ones above. */
    val extraOptions: String = "",
) {
    fun parsedExtraOptions(): Map<String, String> = extraOptions.split(',')
        .map { it.trim() }
        .filter { '=' in it }
        .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }

    companion object {
        /**
         * A request from `-Dspike.source=...` and friends (`.\gradlew.bat ... -Pspike.source=...`), or null. The environment
         * variable `SPIKE_SOURCE` wins over `spike.source`, so a URL with an API key stays out of Gradle's caches.
         */
        fun fromSystemProperties(): PlayRequest? {
            val source = System.getenv("SPIKE_SOURCE")?.takeIf { it.isNotBlank() }
                ?: System.getProperty("spike.source")?.takeIf { it.isNotBlank() }
                ?: return null
            fun prop(name: String) = System.getProperty("spike.$name")?.takeIf { it.isNotBlank() }
            fun flag(name: String, default: Boolean) = prop(name)?.toBooleanStrictOrNull() ?: default
            val defaults = PlayRequest(source)
            return PlayRequest(
                source = source,
                engine = prop("engine") ?: defaults.engine,
                hwdec = prop("hwdec") ?: defaults.hwdec,
                vo = prop("vo") ?: defaults.vo,
                gpuApi = prop("gpuApi") ?: defaults.gpuApi,
                hdrPassthrough = flag("hdr", defaults.hdrPassthrough),
                mediaControls = flag("mediaControls", defaults.mediaControls),
                embed = flag("embed", defaults.embed),
                controlsWindow = flag("controlsWindow", defaults.controlsWindow),
                stats = flag("stats", defaults.stats),
                fullscreen = prop("fullscreen") ?: defaults.fullscreen,
                selfTest = flag("selfTest", defaults.selfTest),
                extraOptions = prop("mpvOptions").orEmpty(),
            )
        }
    }
}

object SpikePaths {
    val mpvDir: File = File(System.getProperty("spike.mpvDir") ?: "mpv")
    val outDir: File = File(System.getProperty("spike.outDir") ?: "spike-out").also { it.mkdirs() }
}

private val secretPatterns = listOf(
    Regex("""(?i)((?:api_?key|token)=)[^&\s"']+"""),
    Regex("""(?i)(Token=")[^"]*"""),
)

/** Strips tokens and API keys from anything the prototype shows or writes. */
fun redact(text: String): String = secretPatterns.fold(text) { acc, regex -> acc.replace(regex, "$1***") }

/** A short name for window titles: the file name, or the URL path without the query. */
fun displayName(source: String): String {
    val withoutQuery = source.substringBefore('?')
    return withoutQuery.substringAfterLast('/').substringAfterLast('\\').ifBlank { redact(source) }
}
