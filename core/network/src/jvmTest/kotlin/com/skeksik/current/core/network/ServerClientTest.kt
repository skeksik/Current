package com.skeksik.current.core.network

import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServiceType
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.net.ServerSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class ServerClientTest {
    private val server = ServerRef(ServiceType.jellyfin, "server-1")
    private val local = ServerUrl.parse("http://192.168.1.10:8096")!!
    private val internet = ServerUrl.parse("https://media.example.com/jellyfin")!!

    @Serializable
    private data class Info(val name: String)

    private class SwitchableAddress(initial: ServerUrl?) : ServerAddressSource {
        override val current = MutableStateFlow(initial)
        val reported = mutableListOf<ServerUrl>()
        override fun reportUnreachable(address: ServerUrl) {
            reported += address
        }
    }

    private class Recorder {
        val requests = mutableListOf<HttpRequestData>()
        var answer: MockRequestHandler = { respond("""{"name":"Test"}""", headers = headersOf(HttpHeaders.ContentType, "application/json")) }
        val handler: MockRequestHandler = { request ->
            requests += request
            answer(request)
        }
    }

    private fun client(
        recorder: Recorder,
        address: ServerAddressSource = FixedServerAddress(internet),
        auth: RequestAuth = RequestAuth.None,
        logger: NetworkLogger = NetworkLogger.None,
    ) = ServerClients(createHttpClient(MockEngine(recorder.handler)), logger, requestTimeout = 200.milliseconds).create(server, address, auth)

    private suspend fun ServerClient.failure(block: suspend io.ktor.client.HttpClient.() -> Unit): ServiceException =
        assertFailsWith<ServiceException> { request(block) }

    @Test
    fun pathsResolveBelowTheAddressWithOrWithoutALeadingSlash() = runBlocking {
        val recorder = Recorder()
        client(recorder).use {
            it.request { get("System/Info/Public?x=1") }
            it.request { get("/Items") }
        }
        assertEquals(
            listOf("https://media.example.com/jellyfin/System/Info/Public?x=1", "https://media.example.com/jellyfin/Items"),
            recorder.requests.map { it.url.toString() },
        )
    }

    @Test
    fun followsTheAddressWhenItChanges() = runBlocking {
        val recorder = Recorder()
        val address = SwitchableAddress(local)
        client(recorder, address).use {
            it.request { get("Items") }
            address.current.value = internet
            it.request { get("Items") }
        }
        assertEquals(
            listOf("http://192.168.1.10:8096/Items", "https://media.example.com/jellyfin/Items"),
            recorder.requests.map { it.url.toString() },
        )
    }

    @Test
    fun noAddressIsUnreachableWithoutARequest() = runBlocking {
        val recorder = Recorder()
        val failure = client(recorder, SwitchableAddress(null)).use { it.failure { get("Items") } }
        assertEquals(ServiceErrorKind.Unreachable, failure.kind)
        assertEquals(emptyList(), recorder.requests)
    }

    @Test
    fun authIsAddedToEveryRequest() = runBlocking {
        val recorder = Recorder()
        client(recorder, auth = { it.header("ApiKey", "secret-key") }).use { it.request { get("Items") } }
        assertEquals("secret-key", recorder.requests.single().headers["ApiKey"])
    }

    @Test
    fun decodesWithTheServiceJson() = runBlocking {
        val info = client(Recorder()).use { it.request { get("info").body<Info>() } }
        assertEquals(Info("Test"), info)
    }

    @Test
    fun statusesMapToKinds() = runBlocking {
        val cases = mapOf(
            HttpStatusCode.Unauthorized to ServiceErrorKind.Unauthorized,
            HttpStatusCode.Forbidden to ServiceErrorKind.Forbidden,
            HttpStatusCode.NotFound to ServiceErrorKind.NotFound,
            HttpStatusCode.BadRequest to ServiceErrorKind.Rejected,
            HttpStatusCode.InternalServerError to ServiceErrorKind.ServerError,
            HttpStatusCode.BadGateway to ServiceErrorKind.ServerError,
        )
        cases.forEach { (status, kind) ->
            val recorder = Recorder().apply { answer = { respond("", status) } }
            val failure = client(recorder).use { it.failure { get("Items") } }
            assertEquals(kind, failure.kind, status.toString())
            assertEquals(status.value, failure.status)
        }
    }

    @Test
    fun redirectsAreNotFollowed() = runBlocking {
        val recorder = Recorder().apply {
            answer = { respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://elsewhere.example.com/login")) }
        }
        val failure = client(recorder, auth = { it.header("ApiKey", "secret-key") }).use { it.failure { get("Items") } }
        assertEquals(ServiceErrorKind.InvalidResponse, failure.kind)
        assertEquals(302, failure.status)
        assertEquals(1, recorder.requests.size)
    }

    @Test
    fun aBodyThatDoesNotDecodeIsAnInvalidResponse() = runBlocking {
        val recorder = Recorder().apply {
            answer = { respond("""{"token":"leaked"}""", headers = headersOf(HttpHeaders.ContentType, "application/json")) }
        }
        val failure = client(recorder).use { it.failure { get("info").body<Info>() } }
        assertEquals(ServiceErrorKind.InvalidResponse, failure.kind)
        assertFalse("leaked" in failure.message.orEmpty())
        assertNull(failure.cause)
    }

    @Test
    fun aSlowServerTimesOutAndIsReported() = runBlocking {
        val recorder = Recorder().apply { answer = { awaitCancellation() } }
        val address = SwitchableAddress(internet)
        val failure = client(recorder, address).use { it.failure { get("Items?ApiKey=secret-key") } }
        assertEquals(ServiceErrorKind.Timeout, failure.kind)
        assertFalse("secret-key" in failure.message.orEmpty())
        assertEquals(listOf(internet), address.reported)
    }

    /** Hands out [first] to the first caller and [then] to any later one, counting the calls. */
    private class ChangingAddress(private val first: ServerUrl, private val then: ServerUrl) : ServerAddressSource {
        override val current = MutableStateFlow<ServerUrl?>(first)
        var waits = 0
        val reported = mutableListOf<ServerUrl>()

        override suspend fun awaitCurrent(): ServerUrl = if (waits++ == 0) first else then

        override fun reportUnreachable(address: ServerUrl) {
            reported += address
        }
    }

    @Test
    fun aRequestWaitsForTheSourceOnceAndReportsTheAddressItWentTo() = runBlocking {
        val recorder = Recorder().apply { answer = { awaitCancellation() } }
        val address = ChangingAddress(internet, local)
        val failure = client(recorder, address).use { it.failure { get("Items") } }
        assertEquals(ServiceErrorKind.Timeout, failure.kind)
        assertEquals(1, address.waits)
        assertEquals(listOf("media.example.com"), recorder.requests.map { it.url.host })
        assertEquals(listOf(internet), address.reported)
    }

    @Test
    fun onTheJvmASecurityExceptionIsABugNotAFailedRequest() = runBlocking {
        // Only Android's refusal for a missing INTERNET permission is a request failure; the JDK's client never throws it.
        val recorder = Recorder().apply { answer = { throw SecurityException("Permission denied (missing INTERNET permission?)") } }
        val address = SwitchableAddress(internet)
        assertFailsWith<SecurityException> { client(recorder, address).use { it.request { get("Items") } } }
        assertEquals(emptyList(), address.reported)
    }

    @Test
    fun aPlatformsConnectionFailureOutsideExceptionIsUnreachableAndReported() = runBlocking {
        // As the browser engine does: a failed fetch is a kotlin.Error, not an Exception.
        class FetchFailure : Error("Fail to fetch")
        val recorder = Recorder().apply { answer = { throw FetchFailure() } }
        val address = SwitchableAddress(internet)
        val engine = MockEngine(recorder.handler)
        val clients = ServerClients(createHttpClient(engine), NetworkLogger.None, 200.milliseconds) { it is FetchFailure }
        val failure = clients.create(server, address).use { it.failure { get("Items") } }
        assertEquals(ServiceErrorKind.Unreachable, failure.kind)
        assertEquals(listOf(internet), address.reported)
    }

    @Test
    fun aRefusedConnectionIsUnreachableAndReported() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val closed = ServerUrl.parse("http://127.0.0.1:$port")!!
        val address = SwitchableAddress(closed)
        val failure = ServerClients(createHttpClient()).create(server, address).use { it.failure { get("Items?ApiKey=secret-key") } }
        assertEquals(ServiceErrorKind.Unreachable, failure.kind)
        assertEquals(listOf(closed), address.reported)
        assertFalse("secret-key" in failure.message.orEmpty() || "127.0.0.1" in failure.message.orEmpty())
    }

    @Test
    fun cancellationPassesThrough() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val recorder = Recorder().apply { answer = { started.complete(Unit); awaitCancellation() } }
        client(recorder).use {
            val call = async { it.request { get("Items") } }
            started.await()
            call.cancel()
            assertFailsWith<CancellationException> { call.await() }
        }
        Unit
    }

    @Test
    fun bugsInTheCallerPassThrough() = runBlocking {
        client(Recorder()).use {
            assertFailsWith<IllegalStateException> { it.request { error("bug") } }
        }
        Unit
    }

    @Test
    fun logsRedactedPathsByServerKeyWithoutHosts() = runBlocking {
        val entries = mutableListOf<RequestLogEntry>()
        val recorder = Recorder()
        client(recorder, auth = { it.url.parameters.append("ApiKey", "secret-key") }, logger = { entries += it }).use {
            it.request { get("Videos/1/stream?static=true") }
        }
        val entry = entries.single()
        assertEquals("/jellyfin/Videos/1/stream?static=true&ApiKey=***", entry.path)
        assertEquals(200, entry.status)
        val line = entry.toString()
        assertFalse("secret-key" in line || "media.example.com" in line, line)
        assertEquals("jellyfin/server-1 GET https://…/jellyfin/Videos/1/stream?static=true&ApiKey=*** 200", line.substringBefore(" ("))
    }

    @Test
    fun logsTheSchemeOfEachAddressWithoutItsHost() = runBlocking {
        val entries = mutableListOf<RequestLogEntry>()
        val address = SwitchableAddress(local)
        client(Recorder(), address, logger = { entries += it }).use {
            it.request { get("System/Info/Public") }
            address.current.value = internet
            it.request { get("System/Info/Public") }
        }
        assertEquals(listOf("http", "https"), entries.map { it.scheme })
        assertEquals(listOf("http://…/System/Info/Public", "https://…/jellyfin/System/Info/Public"), entries.map { it.toString().split(' ')[2] })
        assertFalse(entries.any { "192.168.1.10" in it.toString() || "8096" in it.toString() || "media.example.com" in it.toString() })
    }

    @Test
    fun logsFailedRequestsWithoutAStatus() = runBlocking {
        val entries = mutableListOf<RequestLogEntry>()
        val recorder = Recorder().apply { answer = { awaitCancellation() } }
        client(recorder, logger = { entries += it }).use { it.failure { get("Items") } }
        val entry = entries.single()
        assertNull(entry.status)
        assertFalse(entry.cancelled)
        assertTrue(entry.toString().endsWith(" no response (${entry.durationMillis} ms)"), entry.toString())
    }

    @Test
    fun logsARequestWhoseCallerWasCancelledAsCancelled() = runBlocking {
        val entries = mutableListOf<RequestLogEntry>()
        val started = CompletableDeferred<Unit>()
        val recorder = Recorder().apply { answer = { started.complete(Unit); awaitCancellation() } }
        client(recorder, logger = { entries += it }).use {
            val call = launch { it.request { get("System/Info/Public") } }
            started.await()
            call.cancelAndJoin()
        }
        val entry = entries.single()
        assertNull(entry.status)
        assertTrue(entry.cancelled)
        assertTrue(entry.toString().endsWith(" cancelled (${entry.durationMillis} ms)"), entry.toString())
    }
}
