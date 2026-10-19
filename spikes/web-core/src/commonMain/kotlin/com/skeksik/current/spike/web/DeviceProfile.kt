package com.skeksik.current.spike.web

/** One question to the browser; [BrowserProbes.queries] lists them all, the page runs them, [BrowserProbes.interpret] reads the answers. */
data class ProbeQuery(val id: String, val kind: Kind, val arg: String) {
    enum class Kind { CanPlayType, IsTypeSupported, DecodingInfo, MatchMedia, MaxChannels }
}

/** A container the browser plays through `<video src>`, with the codecs it plays in it (Jellyfin names). */
data class ContainerSupport(val container: String, val video: List<String>, val audio: List<String>)

/**
 * What this browser can play, from runtime probes only (nothing assumed per browser):
 * - [direct]: containers and codecs for `<video src>` (direct play of the file).
 * - [hlsVideo]/[hlsAudio]: codecs the server may copy into fMP4 HLS, because MSE (hls.js) or native HLS takes them; the first is the one
 *   the server encodes to when it must transcode.
 * - [tenBit], [ranges], [uhd]: per video codec, 10-bit decoding, the dynamic ranges to accept (HDR only on an HDR display) and 2160p.
 * - [maxAudioChannels]: channels of the audio output.
 */
data class MediaSupport(
    val direct: List<ContainerSupport>,
    val hlsVideo: List<String>,
    val hlsAudio: List<String>,
    val tenBit: Set<String>,
    val ranges: Map<String, List<String>>,
    val uhd: Set<String>,
    val maxAudioChannels: Int,
    val nativeHls: Boolean,
    val mse: Boolean,
    val probes: Map<String, String> = emptyMap(),
) {
    /**
     * HLS `VIDEO-RANGE`s this device shows. For an HDR source, Jellyfin's master playlist offers the copied HDR stream (PQ) next to
     * tone-mapped SDR variants at the same bandwidth; hls.js must be told to prefer HDR, or it picks SDR and the server tone-maps for nothing.
     */
    val hlsVideoRanges: List<String>
        get() = buildList {
            add("SDR")
            if (ranges.values.any { "HDR10" in it }) add("PQ")
            if (ranges.values.any { "HLG" in it }) add("HLG")
        }
}

/** The probe table. Codec strings are the RFC 6381 forms browsers recognise; levels are high enough for 4K. */
object BrowserProbes {
    /**
     * Video codecs by Jellyfin name; a "10" suffix is the 10-bit profile of the same codec. Where browsers disagree on the codec string,
     * every spelling is asked (query ids `id`, `id#1`, ...) and any yes counts.
     */
    private val video = linkedMapOf(
        "h264" to listOf("avc1.640033"), "h26410" to listOf("avc1.6e0033"),
        "hevc" to listOf("hvc1.1.6.L153.B0", "hev1.1.6.L153.B0"), "hevc10" to listOf("hvc1.2.4.L153.B0", "hev1.2.4.L153.B0"),
        "av1" to listOf("av01.0.12M.08"), "av110" to listOf("av01.0.12M.10"),
        "vp9" to listOf("vp09.00.50.08"), "vp910" to listOf("vp09.02.50.10"),
        "vp8" to listOf("vp8"),
    )
    private val audio = linkedMapOf(
        "aac" to listOf("mp4a.40.2"), "mp3" to listOf("mp4a.6B", "mp4a.69", "mp3"), "ac3" to listOf("ac-3"), "eac3" to listOf("ec-3"),
        "opus" to listOf("opus"), "flac" to listOf("flac", "fLaC"), "vorbis" to listOf("vorbis"), "alac" to listOf("alac"),
        "dts" to listOf("dtsc"), "truehd" to listOf("mlpa"),
    )

    private fun MutableList<ProbeQuery>.ask(id: String, kind: ProbeQuery.Kind, spellings: List<String>, arg: (String) -> String) =
        spellings.forEachIndexed { i, codec -> add(ProbeQuery(if (i == 0) id else "$id#$i", kind, arg(codec))) }

    /** Jellyfin container names, their MIME type and which codecs can be inside. */
    private class Container(val name: String, val mime: String, val video: List<String>, val audio: List<String>)

    private val containers = listOf(
        Container("mp4,m4v", "video/mp4", listOf("h264", "h26410", "hevc", "hevc10", "av1", "av110", "vp9", "vp910"),
            listOf("aac", "mp3", "ac3", "eac3", "opus", "flac", "alac", "dts", "truehd")),
        Container("webm", "video/webm", listOf("vp8", "vp9", "vp910", "av1", "av110"), listOf("opus", "vorbis")),
        Container("mkv", "video/x-matroska", video.keys.toList(), audio.keys.toList()),
        Container("mov", "video/quicktime", listOf("h264", "hevc", "hevc10"), listOf("aac", "mp3", "alac", "ac3", "eac3")),
        Container("ts,mpegts", "video/mp2t", listOf("h264", "hevc", "hevc10"), listOf("aac", "mp3", "ac3", "eac3")),
    )

    /** Codecs the server can put into fMP4 HLS segments. */
    private val hlsVideoCodecs = listOf("h264", "hevc", "av1")
    private val hlsAudioCodecs = listOf("aac", "mp3", "ac3", "eac3", "opus", "flac", "alac")

    private fun hdrConfig(codec: String, transfer: String) =
        """{"type":"file","video":{"contentType":"video/mp4; codecs=\"$codec\"","width":3840,"height":2160,"bitrate":40000000,""" +
            """"framerate":24,"hdrMetadataType":"smpteSt2086","colorGamut":"rec2020","transferFunction":"$transfer"}}"""

    private fun uhdConfig(codec: String) =
        """{"type":"file","video":{"contentType":"video/mp4; codecs=\"$codec\"","width":3840,"height":2160,"bitrate":40000000,"framerate":30}}"""

    val queries: List<ProbeQuery> = buildList {
        for (c in containers) {
            for (v in c.video) ask("play:${c.name}:$v", ProbeQuery.Kind.CanPlayType, video.getValue(v)) { "${c.mime}; codecs=\"$it\"" }
            for (a in c.audio) ask("play:${c.name}:$a", ProbeQuery.Kind.CanPlayType, audio.getValue(a)) { "${c.mime}; codecs=\"$it\"" }
        }
        for ((v, spellings) in video) ask("mse:$v", ProbeQuery.Kind.IsTypeSupported, spellings) { "video/mp4; codecs=\"$it\"" }
        for ((a, spellings) in audio) ask("mse:$a", ProbeQuery.Kind.IsTypeSupported, spellings) { "audio/mp4; codecs=\"$it\"" }
        add(ProbeQuery("hls", ProbeQuery.Kind.CanPlayType, "application/vnd.apple.mpegurl"))
        add(ProbeQuery("display:hdr", ProbeQuery.Kind.MatchMedia, "(dynamic-range: high)"))
        for (v in listOf("hevc10", "av110", "vp910")) {
            add(ProbeQuery("hdr10:$v", ProbeQuery.Kind.DecodingInfo, hdrConfig(video.getValue(v).first(), "pq")))
            add(ProbeQuery("hlg:$v", ProbeQuery.Kind.DecodingInfo, hdrConfig(video.getValue(v).first(), "hlg")))
        }
        for (v in listOf("h264", "hevc", "av1", "vp9")) add(ProbeQuery("uhd:$v", ProbeQuery.Kind.DecodingInfo, uhdConfig(video.getValue(v).first())))
        add(ProbeQuery("channels", ProbeQuery.Kind.MaxChannels, ""))
    }

    /** `canPlayType` says "probably" or "maybe" for yes; the others "true", or "smooth"/"supported" for `decodingInfo`. */
    private fun yes(answer: String?) = answer != null && answer in setOf("probably", "maybe", "true", "smooth", "supported")

    /** The 8-bit and 10-bit probes collapse into one Jellyfin codec name. */
    private fun base(key: String) = key.removeSuffix("10")

    fun interpret(answers: Map<String, String>): MediaSupport {
        /** Yes to [id] under any of its spellings. */
        fun ok(id: String) = yes(answers[id]) || answers.any { (k, a) -> k.startsWith("$id#") && yes(a) }
        val direct = containers.mapNotNull { c ->
            val v = c.video.filter { ok("play:${c.name}:$it") }.map(::base).distinct()
            val a = c.audio.filter { ok("play:${c.name}:$it") }
            if (v.isEmpty()) null else ContainerSupport(c.name, v, a)
        }
        val mse = ok("mse:h264")
        val nativeHls = ok("hls")
        val hlsVideo = when {
            mse -> hlsVideoCodecs.filter { ok("mse:$it") || ok("mse:${it}10") }
            nativeHls -> hlsVideoCodecs.filter { ok("play:mp4,m4v:$it") || ok("play:mp4,m4v:${it}10") }
            else -> emptyList()
        }.ifEmpty { listOf("h264") }
        val hlsAudio = when {
            mse -> hlsAudioCodecs.filter { ok("mse:$it") }
            else -> hlsAudioCodecs.filter { ok("play:mp4,m4v:$it") }
        }.let { listOf("aac") + (it - "aac") }
        val tenBit = video.keys.filter { key -> key.endsWith("10") && (ok("mse:$key") || containers.any { c -> ok("play:${c.name}:$key") }) }
            .map(::base).toSet()
        val hdrDisplay = answers["display:hdr"] == "true"
        val ranges = listOf("h264", "hevc", "av1", "vp9", "vp8").associateWith { v ->
            buildList {
                add("SDR"); add("DOVIWithSDR")
                if (hdrDisplay && yes(answers["hdr10:${v}10"])) addAll(listOf("HDR10", "HDR10Plus", "DOVIWithHDR10"))
                if (hdrDisplay && yes(answers["hlg:${v}10"])) addAll(listOf("HLG", "DOVIWithHLG"))
            }
        }
        val uhd = listOf("h264", "hevc", "av1", "vp9").filter { yes(answers["uhd:$it"]) }.toSet()
        val channels = answers["channels"]?.toIntOrNull()?.coerceIn(2, 8) ?: 2
        return MediaSupport(direct, hlsVideo, hlsAudio, tenBit, ranges, uhd, channels, nativeHls, mse, answers)
    }
}

/**
 * The web device profile, entirely from [MediaSupport]:
 * - Direct play: each container with the codecs the browser plays in it.
 * - Server HLS in fMP4: the video and audio codecs MSE takes are copied (remux), anything else is encoded to the first of each
 *   list (H.264, AAC); channels capped at the output's.
 * - Codec conditions per video codec: bit depth, dynamic range (HDR only on an HDR display that decodes it, else the server tone-maps),
 *   and resolution (2160p only where the browser decodes it).
 * - Subtitles: text as external WebVTT; ASS/SSA and image subtitles burned in (no browser renders them).
 */
internal object WebDeviceProfile {
    const val MAX_BITRATE = 120_000_000L

    fun build(s: MediaSupport): DeviceProfileDto {
        val directPlay = s.direct.map { DirectPlayProfileDto(it.container, "Video", it.video.joinToString(","), it.audio.joinToString(",")) }
        val transcoding = listOf(
            TranscodingProfileDto(
                container = "mp4",
                type = "Video",
                videoCodec = s.hlsVideo.joinToString(","),
                audioCodec = s.hlsAudio.joinToString(","),
                protocol = "hls",
                context = "Streaming",
                maxAudioChannels = s.maxAudioChannels.toString(),
                minSegments = 1,
                breakOnNonKeyFrames = true,
            ),
        )
        val videoCodecs = (s.direct.flatMap { it.video } + s.hlsVideo).distinct()
        val codecProfiles = videoCodecs.map { codec ->
            val uhd = codec in s.uhd
            CodecProfileDto(
                type = "Video",
                codec = codec,
                conditions = listOf(
                    ProfileConditionDto("LessThanEqual", "VideoBitDepth", if (codec in s.tenBit) "10" else "8"),
                    ProfileConditionDto("EqualsAny", "VideoRangeType", s.ranges[codec].orEmpty().ifEmpty { listOf("SDR") }.joinToString("|")),
                    ProfileConditionDto("LessThanEqual", "Width", if (uhd) "3840" else "1920"),
                    ProfileConditionDto("LessThanEqual", "Height", if (uhd) "2160" else "1080"),
                ),
            )
        }
        val subtitles = listOf(
            SubtitleProfileDto("vtt", "External"),
            SubtitleProfileDto("ass", "Encode"),
            SubtitleProfileDto("ssa", "Encode"),
            SubtitleProfileDto("pgssub", "Encode"),
            SubtitleProfileDto("dvdsub", "Encode"),
            SubtitleProfileDto("dvbsub", "Encode"),
        )
        return DeviceProfileDto(
            name = "Current web spike",
            maxStreamingBitrate = MAX_BITRATE,
            maxStaticBitrate = MAX_BITRATE,
            musicStreamingTranscodingBitrate = 384_000,
            directPlayProfiles = directPlay,
            transcodingProfiles = transcoding,
            codecProfiles = codecProfiles,
            subtitleProfiles = subtitles,
        )
    }
}
