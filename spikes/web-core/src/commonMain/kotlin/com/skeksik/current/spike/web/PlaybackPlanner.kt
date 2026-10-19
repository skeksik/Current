package com.skeksik.current.spike.web

import io.ktor.http.encodeURLParameter

enum class PlayMethod(val label: String) {
    DirectPlay("Direct play"),
    ServerHls("Server HLS"),
}

/** Subtitle index meaning "off" in Jellyfin requests. */
const val SUBTITLES_OFF = -1

/** One playable route for the current item and track choice. URLs are absolute and carry the token as `ApiKey`. */
data class PlaybackSource(
    val url: String,
    val isHls: Boolean,
    val method: PlayMethod,
    val mediaSourceId: String,
    val playSessionId: String?,
    val audioTracks: List<Track>,
    val subtitleTracks: List<Track>,
    val audioIndex: Int?,
    val subtitleIndex: Int,
    /** WebVTT to add as a `<track>`, when the selected subtitle is delivered externally (burned-in ones are part of the video). */
    val subtitleUrl: String?,
    val defaultAudioIndex: Int?,
    val transcodeReasons: String?,
)

/** What the user asked for; null means "the server's default". */
data class TrackChoice(val mediaSourceId: String? = null, val audioIndex: Int? = null, val subtitleIndex: Int? = null)

internal object PlaybackPlanner {
    /**
     * Browsers play only the default audio track of a file, so choosing another one turns direct play off and lets the server remux or
     * transcode with that track.
     */
    fun request(
        userId: String,
        profile: DeviceProfileDto,
        choice: TrackChoice,
        defaultAudioIndex: Int?,
    ): PlaybackInfoRequestDto {
        val otherAudio = choice.audioIndex != null && defaultAudioIndex != null && choice.audioIndex != defaultAudioIndex
        return PlaybackInfoRequestDto(
            userId = userId,
            maxStreamingBitrate = WebDeviceProfile.MAX_BITRATE,
            audioStreamIndex = choice.audioIndex,
            subtitleStreamIndex = choice.subtitleIndex,
            mediaSourceId = choice.mediaSourceId,
            deviceProfile = profile,
            enableDirectPlay = !otherAudio,
        )
    }

    fun plan(serverUrl: String, token: String, deviceId: String, itemId: String, info: PlaybackInfoResponseDto, choice: TrackChoice): Result<PlaybackSource> {
        val source = info.mediaSources.firstOrNull { it.id == choice.mediaSourceId } ?: info.mediaSources.firstOrNull()
            ?: return Result.failure(IllegalStateException("The server returned no media source (${info.errorCode ?: "no error code"})."))

        val (url, isHls, method) = when {
            source.supportsDirectPlay -> {
                val container = source.container.orEmpty().substringBefore(',').ifEmpty { "mp4" }
                val query = listOf(
                    "static" to "true",
                    "mediaSourceId" to source.id,
                    "deviceId" to deviceId,
                    "ApiKey" to token,
                ).joinToString("&") { (k, v) -> "$k=${v.encodeURLParameter()}" }
                Triple("$serverUrl/Videos/$itemId/stream.$container?$query", false, PlayMethod.DirectPlay)
            }
            source.transcodingUrl != null -> {
                val hls = source.transcodingSubProtocol.equals("hls", ignoreCase = true) || ".m3u8" in source.transcodingUrl
                Triple(serverUrl + source.transcodingUrl, hls, PlayMethod.ServerHls)
            }
            else -> return Result.failure(
                IllegalStateException("The server offers no way to play this in a browser (${info.errorCode ?: "no transcoding URL"})."),
            )
        }

        val audio = source.mediaStreams.filter { it.type == "Audio" }
        val subtitles = source.mediaStreams.filter { it.type == "Subtitle" }
        val audioIndex = choice.audioIndex ?: source.defaultAudioStreamIndex ?: audio.firstOrNull { it.isDefault }?.index ?: audio.firstOrNull()?.index
        val subtitleIndex = choice.subtitleIndex ?: source.defaultSubtitleStreamIndex ?: SUBTITLES_OFF
        val selectedSubtitle = subtitles.firstOrNull { it.index == subtitleIndex }
        val subtitleUrl = selectedSubtitle
            ?.takeIf { it.deliveryMethod == "External" }
            ?.deliveryUrl
            ?.let { if (it.startsWith("http")) it else serverUrl + it }

        return Result.success(
            PlaybackSource(
                url = url,
                isHls = isHls,
                method = method,
                mediaSourceId = source.id,
                playSessionId = info.playSessionId,
                audioTracks = audio.map { it.toTrack() },
                subtitleTracks = subtitles.map { it.toTrack() },
                audioIndex = audioIndex,
                subtitleIndex = if (selectedSubtitle == null) SUBTITLES_OFF else subtitleIndex,
                subtitleUrl = subtitleUrl,
                defaultAudioIndex = source.defaultAudioStreamIndex,
                transcodeReasons = source.transcodeReasons?.toString(),
            ),
        )
    }

    private fun MediaStreamDto.toTrack() = Track(
        index = index,
        label = displayTitle ?: listOfNotNull(language, codec).joinToString(" ").ifEmpty { "Track $index" },
        isDefault = isDefault,
    )
}
