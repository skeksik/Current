package com.skeksik.current.spike.web

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNamingStrategy

/** Jellyfin's default JSON is PascalCase; nulls are left out both ways. */
@OptIn(ExperimentalSerializationApi::class)
internal val JellyfinJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = true
    namingStrategy = JsonNamingStrategy { _, _, name -> name.replaceFirstChar { it.uppercaseChar() } }
}

@Serializable
internal data class AuthenticateByNameDto(val username: String, val pw: String)

@Serializable
internal data class AuthenticationResultDto(val accessToken: String, val user: UserDto, val serverId: String? = null)

@Serializable
internal data class UserDto(val id: String, val name: String = "")

@Serializable
internal data class ItemsDto(val items: List<ItemDto> = emptyList(), val totalRecordCount: Int = 0)

@Serializable
internal data class ItemDto(
    val id: String,
    val name: String? = null,
    val type: String? = null,
    val productionYear: Int? = null,
    val overview: String? = null,
    val imageTags: Map<String, String> = emptyMap(),
    val primaryImageAspectRatio: Double? = null,
    val isFolder: Boolean = false,
    val mediaType: String? = null,
    val runTimeTicks: Long? = null,
    val seriesName: String? = null,
    val collectionType: String? = null,
)

@Serializable
internal data class PlaybackInfoRequestDto(
    val userId: String,
    val maxStreamingBitrate: Long,
    val startTimeTicks: Long? = null,
    val audioStreamIndex: Int? = null,
    val subtitleStreamIndex: Int? = null,
    val mediaSourceId: String? = null,
    val deviceProfile: DeviceProfileDto,
    val enableDirectPlay: Boolean = true,
    val enableDirectStream: Boolean = true,
    val enableTranscoding: Boolean = true,
    val allowVideoStreamCopy: Boolean = true,
    val allowAudioStreamCopy: Boolean = true,
    val autoOpenLiveStream: Boolean = false,
)

@Serializable
internal data class PlaybackInfoResponseDto(
    val mediaSources: List<MediaSourceDto> = emptyList(),
    val playSessionId: String? = null,
    val errorCode: String? = null,
)

@Serializable
internal data class MediaSourceDto(
    val id: String,
    val container: String? = null,
    val supportsDirectPlay: Boolean = false,
    val supportsDirectStream: Boolean = false,
    val supportsTranscoding: Boolean = false,
    val transcodingUrl: String? = null,
    val transcodingSubProtocol: String? = null,
    val transcodeReasons: JsonElement? = null,
    val mediaStreams: List<MediaStreamDto> = emptyList(),
    val defaultAudioStreamIndex: Int? = null,
    val defaultSubtitleStreamIndex: Int? = null,
    val runTimeTicks: Long? = null,
)

@Serializable
internal data class MediaStreamDto(
    val type: String? = null,
    val index: Int,
    val codec: String? = null,
    val language: String? = null,
    val displayTitle: String? = null,
    val isDefault: Boolean = false,
    val isExternal: Boolean = false,
    val deliveryMethod: String? = null,
    val deliveryUrl: String? = null,
)

@Serializable
internal data class DeviceProfileDto(
    val name: String,
    val maxStreamingBitrate: Long,
    val maxStaticBitrate: Long,
    val musicStreamingTranscodingBitrate: Long,
    val directPlayProfiles: List<DirectPlayProfileDto>,
    val transcodingProfiles: List<TranscodingProfileDto>,
    val containerProfiles: List<JsonElement> = emptyList(),
    val codecProfiles: List<CodecProfileDto> = emptyList(),
    val subtitleProfiles: List<SubtitleProfileDto>,
)

@Serializable
internal data class DirectPlayProfileDto(val container: String, val type: String, val videoCodec: String?, val audioCodec: String?)

@Serializable
internal data class TranscodingProfileDto(
    val container: String,
    val type: String,
    val videoCodec: String,
    val audioCodec: String,
    val protocol: String,
    val context: String,
    val maxAudioChannels: String,
    val minSegments: Int,
    val breakOnNonKeyFrames: Boolean,
)

@Serializable
internal data class SubtitleProfileDto(val format: String, val method: String)

@Serializable
internal data class CodecProfileDto(val type: String, val codec: String, val conditions: List<ProfileConditionDto>)

@Serializable
internal data class ProfileConditionDto(val condition: String, val property: String, val value: String, val isRequired: Boolean = false)
