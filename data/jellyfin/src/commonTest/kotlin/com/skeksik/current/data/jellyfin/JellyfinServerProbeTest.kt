package com.skeksik.current.data.jellyfin

import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServiceType
import com.skeksik.current.core.network.RequestLogEntry
import com.skeksik.current.core.network.ServerClients
import com.skeksik.current.core.network.ServerIdentity
import com.skeksik.current.core.network.ServerUrl
import com.skeksik.current.core.network.ServiceErrorKind
import com.skeksik.current.core.network.ServiceException
import com.skeksik.current.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JellyfinServerProbeTest {
    private val server = ServerRef(ServiceType.jellyfin, "server-1")
    private val address = ServerUrl.parse("https://media.example.com/jellyfin")!!
    private val requests = mutableListOf<HttpRequestData>()

    private val entries = mutableListOf<RequestLogEntry>()

    private fun probe(answer: MockRequestHandler) =
        JellyfinServerProbe(ServerClients(createHttpClient(MockEngine { requests += it; answer(it) }), logger = { entries += it }))

    private fun json(body: String): MockRequestHandler =
        { respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json")) }

    @Test
    fun readsTheIdNameAndVersionWithoutCredentials() = runTest {
        val body = """{"LocalAddress":"http://172.17.0.2:8096","ServerName":"Home","Version":"12.2.0","ProductName":"Jellyfin Server",
            |"OperatingSystem":"","Id":"0123456789abcdef0123456789abcdef","StartupWizardCompleted":true}""".trimMargin()
        val identity = probe(json(body)).identify(server, address)
        assertEquals(ServerIdentity("0123456789abcdef0123456789abcdef", "Home", "12.2.0"), identity)
        val request = requests.single()
        assertEquals("https://media.example.com/jellyfin/System/Info/Public", request.url.toString())
        assertNull(request.headers[HttpHeaders.Authorization])
    }

    @Test
    fun anAnswerWithoutAnIdIsNotJellyfin() = runTest {
        val failure = assertFailsWith<ServiceException> { probe(json("""{"ServerName":"Home"}""")).identify(server, address) }
        assertEquals(ServiceErrorKind.InvalidResponse, failure.kind)
        val blank = assertFailsWith<ServiceException> { probe(json("""{"Id":""}""")).identify(server, address) }
        assertEquals(ServiceErrorKind.InvalidResponse, blank.kind)
    }

    @Test
    fun aWebPageIsNotJellyfin() = runTest {
        val page: MockRequestHandler = { respond("<html></html>", headers = headersOf(HttpHeaders.ContentType, "text/html")) }
        assertEquals(ServiceErrorKind.InvalidResponse, assertFailsWith<ServiceException> { probe(page).identify(server, address) }.kind)
    }

    @Test
    fun aRedirectIsReportedNotFollowed() = runTest {
        val redirect: MockRequestHandler = {
            respond("", HttpStatusCode.MovedPermanently, headersOf(HttpHeaders.Location, "/jellyfin/System/Info/Public"))
        }
        val failure = assertFailsWith<ServiceException> { probe(redirect).identify(server, address) }
        assertEquals(ServiceErrorKind.InvalidResponse, failure.kind)
        assertEquals(301, failure.status)
        assertEquals(1, requests.size)
    }

    @Test
    fun anotherServerBehindTheSameAddressIsReturnedForTheCallerToCompare() = runTest {
        val identity = probe(json("""{"Id":"fedcba9876543210fedcba9876543210","ServerName":"Other"}""")).identify(server, address)
        assertEquals("fedcba9876543210fedcba9876543210", identity.serverId)
    }

    @Test
    fun aProxyLoginPageIsNotJellyfin() = runTest {
        val login: MockRequestHandler = {
            respond("<html><form>Sign in</form></html>", headers = headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8"))
        }
        val failure = assertFailsWith<ServiceException> { probe(login).identify(server, address) }
        assertEquals(ServiceErrorKind.InvalidResponse, failure.kind)
        assertNull(failure.status)
    }

    @Test
    fun aRedirectToALoginPageIsReportedWithItsStatus() = runTest {
        val redirect: MockRequestHandler = {
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://auth.example.com/login"))
        }
        val failure = assertFailsWith<ServiceException> { probe(redirect).identify(server, address) }
        assertEquals(ServiceErrorKind.InvalidResponse, failure.kind)
        assertEquals(302, failure.status)
        assertEquals(1, requests.size)
        assertEquals(302, entries.single().status)
    }

    /** What a roaming check does to a lower-ranked probe once a higher address is proved: it is not a failure, and the log says so. */
    @Test
    fun aCancelledProbeIsLoggedAsCancelledNotAsNoResponse() = runTest {
        val started = CompletableDeferred<Unit>()
        val probe = probe { started.complete(Unit); awaitCancellation() }
        val call = launch { probe.identify(server, address) }
        started.await()
        call.cancelAndJoin()
        val entry = entries.single()
        assertNull(entry.status)
        assertTrue(entry.cancelled, entry.toString())
    }
}
