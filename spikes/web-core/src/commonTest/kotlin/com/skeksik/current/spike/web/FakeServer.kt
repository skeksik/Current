package com.skeksik.current.spike.web

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

const val SERVER = "https://jellyfin.example.org"
const val ITEM_ID = "0123456789abcdef0123456789abcdef"
const val USER_ID = "fedcba9876543210fedcba9876543210"

/** A plain browser: MP4/WebM direct play with H.264/AV1/VP9, HLS with H.264 and AAC only, SDR, 1080p, stereo. */
val allSupport = MediaSupport(
    direct = listOf(
        ContainerSupport("mp4,m4v", listOf("h264", "av1", "vp9"), listOf("aac", "mp3", "opus", "flac")),
        ContainerSupport("webm", listOf("vp8", "vp9", "av1"), listOf("opus", "vorbis")),
    ),
    hlsVideo = listOf("h264"),
    hlsAudio = listOf("aac"),
    tenBit = emptySet(),
    ranges = emptyMap(),
    uhd = emptySet(),
    maxAudioChannels = 2,
    nativeHls = false,
    mse = true,
)

/** A Jellyfin stand-in: answers by path and records every request with its JSON body. */
class FakeServer(dispatcher: CoroutineDispatcher) {
    val requests = mutableListOf<Pair<HttpRequestData, JsonObject?>>()
    var handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { respondJson("{}") }

    val engine = MockEngine.create {
        this.dispatcher = dispatcher
        addHandler { request ->
            val body = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString()
            requests += request to body?.let { JellyfinJson.parseToJsonElement(it).jsonObject }
            handler(request)
        }
    }

    fun requestsTo(pathEnd: String) = requests.filter { it.first.url.encodedPath.endsWith(pathEnd) }

    fun graph(store: KeyValueStore = InMemoryKeyValueStore(), scope: CoroutineScope) =
        AppGraph(store, engine, CompletableDeferred(allSupport), "Test", scope)
}

fun MockRequestHandleScope.respondJson(json: String, status: HttpStatusCode = HttpStatusCode.OK) =
    respond(json, status, headersOf(HttpHeaders.ContentType, "application/json"))

fun signedInStore(viaApiKey: Boolean = false) = InMemoryKeyValueStore().apply {
    set(
        AppGraph.KEY_SESSION,
        """{"ServerUrl":"$SERVER","AccessToken":"token1","UserId":"$USER_ID","UserName":"me","ViaApiKey":$viaApiKey}""",
    )
}

fun playbackInfoJson(
    directPlay: Boolean,
    transcodingUrl: String? = null,
    subtitleDelivery: String = "External",
    playSessionId: String = "ps1",
) = """
{
  "MediaSources": [{
    "Id": "$ITEM_ID", "Container": "mp4", "SupportsDirectPlay": $directPlay, "SupportsTranscoding": true,
    ${transcodingUrl?.let { "\"TranscodingUrl\": \"$it\", \"TranscodingSubProtocol\": \"hls\"," } ?: ""}
    "DefaultAudioStreamIndex": 1,
    "MediaStreams": [
      {"Type": "Video", "Index": 0, "Codec": "h264"},
      {"Type": "Audio", "Index": 1, "Codec": "aac", "Language": "eng", "DisplayTitle": "English - AAC - Stereo", "IsDefault": true},
      {"Type": "Audio", "Index": 2, "Codec": "ac3", "Language": "ger", "DisplayTitle": "German - Dolby Digital - 5.1"},
      {"Type": "Subtitle", "Index": 3, "Codec": "subrip", "Language": "eng", "DisplayTitle": "English - SUBRIP",
       "DeliveryMethod": "$subtitleDelivery", "DeliveryUrl": "/Videos/$ITEM_ID/$ITEM_ID/Subtitles/3/0/Stream.vtt?ApiKey=token1"},
      {"Type": "Subtitle", "Index": 4, "Codec": "ass", "Language": "eng", "DisplayTitle": "English - ASS", "DeliveryMethod": "Encode"}
    ]
  }],
  "PlaySessionId": "$playSessionId"
}
""".trimIndent()
