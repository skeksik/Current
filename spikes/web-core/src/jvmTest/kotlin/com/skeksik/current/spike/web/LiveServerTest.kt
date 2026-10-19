package com.skeksik.current.spike.web

import io.ktor.client.call.body
import io.ktor.client.engine.java.Java
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Opt-in, read-only check of the core against a real Jellyfin (`-Pspike.live=true`, server and API key from a file outside the repo).
 * Only GETs and PlaybackInfo (which starts no transcode). Writes a report without the server address to build/live-report.txt.
 */
class LiveServerTest {
    @Serializable
    private data class LiveUser(val id: String, val name: String = "")

    @Serializable
    private data class LiveItems(val items: List<LiveItem> = emptyList())

    @Serializable
    private data class LiveItem(val id: String, val type: String? = null, val mediaSources: List<MediaSourceDto> = emptyList())

    private val browsers = mapOf(
        "Edge 155 (recorded probes)" to BrowserProbes.interpret(ProbeFixtures.edge),
        "Chrome 155 (recorded probes)" to BrowserProbes.interpret(ProbeFixtures.chrome),
        "Firefox-like (Chrome without MKV and HEVC; not recorded)" to BrowserProbes.interpret(ProbeFixtures.firefoxLike),
    )

    @Test
    fun playbackRoutesFromTheWebDeviceProfile() = runBlocking {
        if (System.getProperty("spike.live") != "true") return@runBlocking
        val env = File(System.getProperty("spike.serverEnv")).readLines()
            .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
        val server = normalizeServerUrl(env.getValue("JELLYFIN_URL"))
        val key = env.getValue("JELLYFIN_API_KEY")
        val http = createHttpClient(Java.create())
        val info = ClientInfo(deviceId = "live-check-0000000000000000000000")
        val auth = authorizationHeader(info, key)
        val report = StringBuilder()
        fun line(text: String) = report.appendLine(text).also { println(text) }

        val users = http.get("$server/Users") { header(HttpHeaders.Authorization, auth) }.body<List<LiveUser>>()
        line("Users: ${users.size}")
        val user = users.maxBy { u ->
            http.get("$server/Items") {
                header(HttpHeaders.Authorization, auth)
                parameter("userId", u.id); parameter("recursive", true); parameter("includeItemTypes", "Movie,Episode"); parameter("limit", 0)
            }.body<kotlinx.serialization.json.JsonObject>()["TotalRecordCount"].toString().toInt()
        }
        val api = JellyfinApi(http, server, info, key)
        val views = api.userViews(user.id)
        line("UserViews with an API key and userId: ${views.size} libraries")
        val firstChildren = views.firstOrNull()?.let { api.children(user.id, it.id) }.orEmpty()
        line("Children of the first library: ${firstChildren.size}, with posters: ${firstChildren.count { it.imageUrl != null }}")

        val items = http.get("$server/Items") {
            header(HttpHeaders.Authorization, auth)
            parameter("userId", user.id)
            parameter("recursive", true)
            parameter("includeItemTypes", "Movie,Episode")
            parameter("fields", "MediaSources")
            parameter("limit", 5000)
        }.body<LiveItems>().items.filter { it.mediaSources.isNotEmpty() }
        line("Video items scanned: ${items.size}")

        fun MediaSourceDto.video() = mediaStreams.firstOrNull { it.type == "Video" }?.codec
        fun MediaSourceDto.audio() = mediaStreams.firstOrNull { it.index == defaultAudioStreamIndex }?.codec
            ?: mediaStreams.firstOrNull { it.type == "Audio" }?.codec
        fun MediaSourceDto.subs() = mediaStreams.filter { it.type == "Subtitle" }
        fun pick(label: String, predicate: (MediaSourceDto) -> Boolean) = items.firstOrNull { predicate(it.mediaSources.first()) }?.let { label to it }

        val samples = listOfNotNull(
            pick("MP4 H.264 AAC") { it.container.orEmpty().contains("mp4") && it.video() == "h264" && it.audio() == "aac" },
            pick("MKV H.264 AAC/Opus") { it.container == "mkv" && it.video() == "h264" && it.audio() in setOf("aac", "opus") },
            pick("MKV HEVC AAC, no default subtitle") { it.container == "mkv" && it.video() == "hevc" && it.audio() == "aac" && it.defaultSubtitleStreamIndex == null },
            pick("AC-3 audio") { it.audio() == "ac3" },
            pick("E-AC-3 audio") { it.audio() == "eac3" },
            pick("DTS audio") { it.audio() == "dts" },
            pick("TrueHD audio") { it.audio() == "truehd" },
            pick("ASS subtitles") { s -> s.subs().any { it.codec == "ass" || it.codec == "ssa" } },
            pick("PGS subtitles") { s -> s.subs().any { it.codec == "pgssub" } },
            pick("SRT subtitles") { s -> s.subs().any { it.codec == "subrip" } },
            pick("Two or more audio tracks") { s -> s.mediaStreams.count { it.type == "Audio" } >= 2 && s.video() == "h264" && s.audio() == "aac" }
                ?: pick("Two or more audio tracks") { s -> s.mediaStreams.count { it.type == "Audio" } >= 2 },
        )

        for ((browser, support) in browsers) {
            line("\n== $browser")
            val profile = WebDeviceProfile.build(support)
            for ((label, item) in samples) {
                val src = item.mediaSources.first()
                val desc = "${src.container}/${src.video()}/${src.audio()}"
                val first = api.playbackInfo(item.id, PlaybackPlanner.request(user.id, profile, TrackChoice(), null))
                val plan = PlaybackPlanner.plan(server, key, info.deviceId, item.id, first, TrackChoice()).getOrNull()
                val ms = first.mediaSources.first()
                line("$label [$desc]: ${plan?.method?.label ?: "no route (${first.errorCode})"}; reasons=${ms.transcodeReasons}")
                if (plan?.method == PlayMethod.ServerHls) {
                    val url = ms.transcodingUrl.orEmpty()
                    val params = url.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
                    val shown = params.filterKeys { k -> !k.equals("ApiKey", true) && !k.equals("DeviceId", true) && !k.equals("PlaySessionId", true) && !k.equals("MediaSourceId", true) && !k.equals("Tag", true) }
                    line("   HLS ${url.substringBefore('?').substringAfterLast('/')} apiKeyInUrl=${"ApiKey=" in url}: $shown")
                }
                when (label) {
                    "ASS subtitles", "PGS subtitles", "SRT subtitles" -> {
                        val codec = mapOf("ASS subtitles" to setOf("ass", "ssa"), "PGS subtitles" to setOf("pgssub"), "SRT subtitles" to setOf("subrip")).getValue(label)
                        val sub = src.subs().first { it.codec in codec }
                        val choice = TrackChoice(mediaSourceId = src.id, subtitleIndex = sub.index)
                        val withSub = api.playbackInfo(item.id, PlaybackPlanner.request(user.id, profile, choice, src.defaultAudioStreamIndex))
                        val chosen = withSub.mediaSources.first()
                        val delivered = chosen.mediaStreams.first { it.index == sub.index }
                        val subPlan = PlaybackPlanner.plan(server, key, info.deviceId, item.id, withSub, choice).getOrNull()
                        line("   subtitle ${sub.codec} #${sub.index}: delivery=${delivered.deliveryMethod} route=${subPlan?.method?.label} " +
                            "subtitleUrl=${subPlan?.subtitleUrl?.substringAfterLast('/')?.substringBefore('?')} " +
                            "burnInUrl=${chosen.transcodingUrl?.contains("SubtitleMethod=Encode")}")
                    }
                    "Two or more audio tracks" -> {
                        val other = src.mediaStreams.first { it.type == "Audio" && it.index != src.defaultAudioStreamIndex }
                        val choice = TrackChoice(mediaSourceId = src.id, audioIndex = other.index)
                        val switched = api.playbackInfo(item.id, PlaybackPlanner.request(user.id, profile, choice, src.defaultAudioStreamIndex))
                        val sp = PlaybackPlanner.plan(server, key, info.deviceId, item.id, switched, choice).getOrNull()
                        val turl = switched.mediaSources.first().transcodingUrl.orEmpty()
                        line("   switch to audio #${other.index} (${other.codec}): route=${sp?.method?.label} AudioStreamIndex in URL=${"AudioStreamIndex=${other.index}" in turl}")
                        // Same choice but direct play left on: what does the server do by itself?
                        val asIs = api.playbackInfo(item.id, PlaybackPlanner.request(user.id, profile, choice, null))
                        line("   same with direct play allowed: supportsDirectPlay=${asIs.mediaSources.first().supportsDirectPlay}")
                    }
                }
            }
        }
        File("build/live-report.txt").writeText(report.toString())
        assertTrue(samples.isNotEmpty())
    }
}
