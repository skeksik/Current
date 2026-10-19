package com.skeksik.current.core.playback.web

import com.skeksik.current.core.playback.CodecProfile
import com.skeksik.current.core.playback.DeviceProfile
import com.skeksik.current.core.playback.DirectPlayProfile
import com.skeksik.current.core.playback.MediaType
import com.skeksik.current.core.playback.ProfileCondition
import com.skeksik.current.core.playback.ProfileCondition.Condition
import com.skeksik.current.core.playback.ProfileCondition.Property
import com.skeksik.current.core.playback.StreamProtocol
import com.skeksik.current.core.playback.SubtitleMethod
import com.skeksik.current.core.playback.SubtitleProfile
import com.skeksik.current.core.playback.TranscodingProfile

/**
 * The web device profile, entirely from [MediaSupport]:
 * - Direct play: each container with the codecs the browser plays in it.
 * - Server HLS in fMP4: the video and audio codecs MSE takes are copied (remux), anything else is encoded to the first of each
 *   list (H.264, AAC); channels capped at the output's.
 * - Codec conditions per video codec: bit depth, dynamic range (HDR only on an HDR display that decodes it, else the server tone-maps),
 *   and resolution (2160p only where the browser decodes it).
 * - Subtitles: text as external WebVTT; ASS/SSA and image subtitles burned in (no browser renders them).
 */
object WebDeviceProfile {
    const val MAX_BITRATE = 120_000_000L

    fun build(s: MediaSupport): DeviceProfile {
        val directPlay = s.direct.map { DirectPlayProfile(it.container, MediaType.Video, it.video, it.audio) }
        val transcoding = listOf(
            TranscodingProfile(
                container = "mp4",
                type = MediaType.Video,
                videoCodecs = s.hlsVideo,
                audioCodecs = s.hlsAudio,
                protocol = StreamProtocol.Hls,
                maxAudioChannels = s.maxAudioChannels,
                minSegments = 1,
                breakOnNonKeyFrames = true,
            ),
        )
        val videoCodecs = (s.direct.flatMap { it.video } + s.hlsVideo).distinct()
        val codecs = videoCodecs.map { codec ->
            val uhd = codec in s.uhd
            CodecProfile(
                type = MediaType.Video,
                codec = codec,
                conditions = listOf(
                    ProfileCondition(Condition.LessThanEqual, Property.VideoBitDepth, if (codec in s.tenBit) "10" else "8"),
                    ProfileCondition(
                        Condition.EqualsAny,
                        Property.VideoRangeType,
                        s.ranges[codec].orEmpty().ifEmpty { listOf("SDR") }.joinToString("|"),
                    ),
                    ProfileCondition(Condition.LessThanEqual, Property.Width, if (uhd) "3840" else "1920"),
                    ProfileCondition(Condition.LessThanEqual, Property.Height, if (uhd) "2160" else "1080"),
                ),
            )
        }
        val subtitles = listOf(SubtitleProfile("vtt", SubtitleMethod.External)) +
            listOf("ass", "ssa", "pgssub", "dvdsub", "dvbsub").map { SubtitleProfile(it, SubtitleMethod.Encode) }
        return DeviceProfile(
            name = "Current web",
            maxStreamingBitrate = MAX_BITRATE,
            maxStaticBitrate = MAX_BITRATE,
            musicStreamingTranscodingBitrate = 384_000,
            directPlay = directPlay,
            transcoding = transcoding,
            codecs = codecs,
            subtitles = subtitles,
        )
    }
}
