package com.skeksik.current.core.network

import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals

class HttpClientFactoryTest {
    @Serializable
    private data class ServerInfo(val name: String)

    @Test
    fun decodesJsonAndIgnoresUnknownFields() = runBlocking {
        val engine = MockEngine {
            respond(
                content = """{"name":"Test server","version":"1.0"}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val info = createHttpClient(engine).use { it.get("https://server.invalid/info").body<ServerInfo>() }
        assertEquals(ServerInfo("Test server"), info)
    }
}
