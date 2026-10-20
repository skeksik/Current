package com.skeksik.current.data.jellyfin

import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServiceType
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
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class JellyfinServerProbeTest {
    private val server = ServerRef(ServiceType.jellyfin, "server-1")
    private val address = ServerUrl.parse("https://media.example.com/jellyfin")!!
    private val requests = mutableListOf<HttpRequestData>()

    private fun probe(answer: MockRequestHandler) =
        JellyfinServerProbe(ServerClients(createHttpClient(MockEngine { requests += it; answer(it) })))

    private fun json(body: String): MockRequestHandler =
        { respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json")) }

    @Test
    fun readsTheIdNameAndVersionWithoutCredentials() = runBlocking {
        val body = """{"LocalAddress":"http://172.17.0.2:8096","ServerName":"Home","Version":"12.2.0","ProductName":"Jellyfin Server",
            |"OperatingSystem":"","Id":"0123456789abcdef0123456789abcdef","StartupWizardCompleted":true}""".trimMargin()
        val identity = probe(json(body)).identify(server, address)
        assertEquals(ServerIdentity("0123456789abcdef0123456789abcdef", "Home", "12.2.0"), identity)
        val request = requests.single()
        assertEquals("https://media.example.com/jellyfin/System/Info/Public", request.url.toString())
        assertNull(request.headers[HttpHeaders.Authorization])
    }

    @Test
    fun anAnswerWithoutAnIdIsNotJellyfin() = runBlocking {
        val failure = assertFailsWith<ServiceException> { probe(json("""{"ServerName":"Home"}""")).identify(server, address) }
        assertEquals(ServiceErrorKind.InvalidResponse, failure.kind)
        val blank = assertFailsWith<ServiceException> { probe(json("""{"Id":""}""")).identify(server, address) }
        assertEquals(ServiceErrorKind.InvalidResponse, blank.kind)
    }

    @Test
    fun aWebPageIsNotJellyfin() = runBlocking {
        val page: MockRequestHandler = { respond("<html></html>", headers = headersOf(HttpHeaders.ContentType, "text/html")) }
        assertEquals(ServiceErrorKind.InvalidResponse, assertFailsWith<ServiceException> { probe(page).identify(server, address) }.kind)
    }

    @Test
    fun aRedirectIsReportedNotFollowed() = runBlocking {
        val redirect: MockRequestHandler = {
            respond("", HttpStatusCode.MovedPermanently, headersOf(HttpHeaders.Location, "/jellyfin/System/Info/Public"))
        }
        val failure = assertFailsWith<ServiceException> { probe(redirect).identify(server, address) }
        assertEquals(ServiceErrorKind.InvalidResponse, failure.kind)
        assertEquals(301, failure.status)
        assertEquals(1, requests.size)
    }
}
