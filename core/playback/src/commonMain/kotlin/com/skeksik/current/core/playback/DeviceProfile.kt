package com.skeksik.current.core.playback

/**
 * What a player can play, in the shape of Jellyfin's device profile (container and codec names are Jellyfin's). The server picks the
 * route from it: direct play, a remux or a transcode. `data:jellyfin` maps it to its request body.
 */
data class DeviceProfile(
    val name: String,
    val maxStreamingBitrate: Long,
    val maxStaticBitrate: Long,
    val musicStreamingTranscodingBitrate: Int,
    val directPlay: List<DirectPlayProfile>,
    val transcoding: List<TranscodingProfile>,
    val codecs: List<CodecProfile>,
    val subtitles: List<SubtitleProfile>,
)

/** A container ("mp4,m4v") played as a file, with the codecs it may contain. */
data class DirectPlayProfile(val container: String, val type: MediaType, val videoCodecs: List<String>, val audioCodecs: List<String>)

/** What the server streams when the file cannot be played as it is: these codecs are copied, anything else is encoded to the first. */
data class TranscodingProfile(
    val container: String,
    val type: MediaType,
    val videoCodecs: List<String>,
    val audioCodecs: List<String>,
    val protocol: StreamProtocol,
    val maxAudioChannels: Int,
    val minSegments: Int,
    val breakOnNonKeyFrames: Boolean,
)

/** Conditions a stream of [codec] must meet to be played without re-encoding. */
data class CodecProfile(val type: MediaType, val codec: String, val conditions: List<ProfileCondition>)

data class ProfileCondition(val condition: Condition, val property: Property, val value: String) {
    enum class Condition { LessThanEqual, EqualsAny }

    enum class Property { VideoBitDepth, VideoRangeType, Width, Height }
}

/** How subtitles in [format] reach the player. */
data class SubtitleProfile(val format: String, val method: SubtitleMethod)

enum class SubtitleMethod {
    /** A separate file the player renders. */
    External,

    /** Burned into the video by the server. */
    Encode,
}

enum class MediaType { Video, Audio }

enum class StreamProtocol { Hls }
