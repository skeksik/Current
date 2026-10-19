package com.skeksik.current.spike.web

/**
 * What the browser said it can play: `canPlayType` (direct play through `src`) and `MediaSource.isTypeSupported` (hls.js).
 * [probes] keeps every raw answer for diagnostics.
 */
data class MediaSupport(
    val h264: Boolean,
    val hevc: Boolean,
    val av1: Boolean,
    val vp9: Boolean,
    val vp8: Boolean,
    val aac: Boolean,
    val mp3: Boolean,
    val opus: Boolean,
    val flac: Boolean,
    val vorbis: Boolean,
    val mkv: Boolean,
    val nativeHls: Boolean,
    val mse: Boolean,
    val probes: Map<String, String> = emptyMap(),
)

/**
 * The web device profile: MP4 and WebM direct play with what the probes allow, MKV only where its probe passes, everything else through
 * the server's HLS (H.264 + AAC in MPEG-TS). AC-3, E-AC-3, DTS and TrueHD are never direct-played, so the server transcodes that audio;
 * ASS/SSA and image subtitles are burned in; text subtitles arrive as external WebVTT.
 */
internal object WebDeviceProfile {
    const val MAX_BITRATE = 120_000_000L

    fun build(s: MediaSupport): DeviceProfileDto {
        fun codecs(vararg pairs: Pair<String, Boolean>) = pairs.filter { it.second }.joinToString(",") { it.first }

        val directPlay = buildList {
            val mp4Video = codecs("h264" to s.h264, "hevc" to s.hevc, "av1" to s.av1, "vp9" to s.vp9)
            val mp4Audio = codecs("aac" to s.aac, "mp3" to s.mp3, "opus" to s.opus, "flac" to s.flac)
            if (mp4Video.isNotEmpty()) add(DirectPlayProfileDto("mp4,m4v", "Video", mp4Video, mp4Audio))
            val webmVideo = codecs("vp8" to s.vp8, "vp9" to s.vp9, "av1" to s.av1)
            val webmAudio = codecs("opus" to s.opus, "vorbis" to s.vorbis)
            if (webmVideo.isNotEmpty()) add(DirectPlayProfileDto("webm", "Video", webmVideo, webmAudio))
            if (s.mkv) {
                val mkvVideo = codecs("h264" to s.h264, "hevc" to s.hevc, "av1" to s.av1, "vp9" to s.vp9, "vp8" to s.vp8)
                val mkvAudio = codecs("aac" to s.aac, "mp3" to s.mp3, "opus" to s.opus, "flac" to s.flac, "vorbis" to s.vorbis)
                if (mkvVideo.isNotEmpty()) add(DirectPlayProfileDto("mkv", "Video", mkvVideo, mkvAudio))
            }
        }
        val transcoding = listOf(
            TranscodingProfileDto(
                container = "ts",
                type = "Video",
                videoCodec = "h264",
                audioCodec = "aac",
                protocol = "hls",
                context = "Streaming",
                maxAudioChannels = "6",
                minSegments = 1,
                breakOnNonKeyFrames = true,
            ),
        )
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
            subtitleProfiles = subtitles,
        )
    }
}
