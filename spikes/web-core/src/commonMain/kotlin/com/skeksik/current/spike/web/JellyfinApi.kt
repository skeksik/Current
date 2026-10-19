package com.skeksik.current.spike.web

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.serialization.kotlinx.json.json
import kotlin.coroutines.cancellation.CancellationException

/** How this client names itself to Jellyfin. One random [deviceId] per browser: signing in again with it revokes the old token. */
data class ClientInfo(
    val deviceId: String,
    val device: String = "Web browser",
    val client: String = "Current web spike",
    val version: String = "0.0.1",
)

/** `MediaBrowser Client="..", Device="..", DeviceId="..", Version="..", Token=".."`: values quoted and URL-encoded. */
internal fun authorizationHeader(info: ClientInfo, token: String?): String {
    val fields = buildList {
        add("Client" to info.client)
        add("Device" to info.device)
        add("DeviceId" to info.deviceId)
        add("Version" to info.version)
        if (token != null) add("Token" to token)
    }
    return "MediaBrowser " + fields.joinToString(", ") { (key, value) -> "$key=\"${value.encodeURLParameter()}\"" }
}

/** `jellyfin.example.org/` -> `https://jellyfin.example.org`. */
fun normalizeServerUrl(input: String): String {
    val trimmed = input.trim().trimEnd('/')
    return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
}

internal fun createHttpClient(engine: HttpClientEngine): HttpClient = HttpClient(engine) {
    expectSuccess = true
    install(ContentNegotiation) { json(JellyfinJson) }
}

/** Failure with a message fit for the UI; [CancellationException] is never caught. */
internal suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}

internal fun Throwable.userMessage(): String = when (this) {
    is ResponseException -> when (response.status) {
        HttpStatusCode.Unauthorized -> "Wrong username or password, or the session has expired."
        HttpStatusCode.Forbidden -> "Not allowed."
        HttpStatusCode.NotFound -> "Not found."
        else -> "The server answered ${response.status.value}."
    }
    else -> "Cannot reach the server (${message ?: this::class.simpleName})."
}

/** The few Jellyfin 12.x endpoints the spike needs. The user is passed explicitly so an API-key session works too. */
internal class JellyfinApi(
    private val http: HttpClient,
    val serverUrl: String,
    private val clientInfo: ClientInfo,
    private val token: String?,
) {
    private fun HttpRequestBuilder.auth() = header(HttpHeaders.Authorization, authorizationHeader(clientInfo, token))

    suspend fun authenticateByName(username: String, password: String): Session {
        val result: AuthenticationResultDto = http.post("$serverUrl/Users/AuthenticateByName") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(AuthenticateByNameDto(username, password))
        }.body()
        return Session(serverUrl, result.accessToken, result.user.id, result.user.name)
    }

    suspend fun logout() {
        http.post("$serverUrl/Sessions/Logout") { auth() }
    }

    suspend fun userViews(userId: String): List<Library> {
        val dto: ItemsDto = http.get("$serverUrl/UserViews") {
            auth()
            parameter("userId", userId)
        }.body()
        return dto.items.map { Library(it.id, it.name.orEmpty(), imageUrl(it, 300)) }
    }

    suspend fun children(userId: String, parentId: String): List<MediaItem> {
        val dto: ItemsDto = http.get("$serverUrl/Items") {
            auth()
            parameter("userId", userId)
            parameter("parentId", parentId)
            parameter("recursive", false)
            parameter("sortBy", "SortName")
            parameter("sortOrder", "Ascending")
            parameter("fields", "PrimaryImageAspectRatio")
            parameter("enableImageTypes", "Primary")
            parameter("limit", 300)
        }.body()
        return dto.items.map { it.toMediaItem() }
    }

    suspend fun item(userId: String, id: String): MediaItem {
        val dto: ItemDto = http.get("$serverUrl/Items/$id") {
            auth()
            parameter("userId", userId)
        }.body()
        return dto.toMediaItem()
    }

    suspend fun playbackInfo(itemId: String, request: PlaybackInfoRequestDto): PlaybackInfoResponseDto =
        http.post("$serverUrl/Items/$itemId/PlaybackInfo") {
            auth()
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    /** Keeps an HLS transcode alive without touching watch state (the server stops it after 60 s without progress or ping). */
    suspend fun ping(playSessionId: String) {
        http.post("$serverUrl/Sessions/Playing/Ping") {
            auth()
            parameter("playSessionId", playSessionId)
        }
    }

    suspend fun stopEncoding(playSessionId: String) {
        http.delete("$serverUrl/Videos/ActiveEncodings") {
            auth()
            parameter("deviceId", clientInfo.deviceId)
            parameter("playSessionId", playSessionId)
        }
    }

    private fun ItemDto.toMediaItem() = MediaItem(
        id = id,
        name = name.orEmpty(),
        type = type.orEmpty(),
        year = productionYear,
        overview = overview,
        imageUrl = imageUrl(this, 450),
        imageAspectRatio = primaryImageAspectRatio ?: (2.0 / 3.0),
        isFolder = isFolder,
        isPlayable = mediaType == "Video",
        runtimeSeconds = runTimeTicks?.let { it / TICKS_PER_SECOND },
        seriesName = seriesName,
    )

    /** Item images need no auth; with `tag` the response is cached as immutable. */
    private fun imageUrl(item: ItemDto, fillHeight: Int): String? =
        item.imageTags["Primary"]?.let { "$serverUrl/Items/${item.id}/Images/Primary?tag=$it&fillHeight=$fillHeight&quality=90" }
}

internal const val TICKS_PER_SECOND = 10_000_000.0
