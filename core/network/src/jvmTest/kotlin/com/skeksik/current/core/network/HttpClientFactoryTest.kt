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

    @Serializable
    private enum class State { Ready, Busy }

    @Serializable
    private data class Item(val name: String, val state: State = State.Ready, val overview: String?, val count: Int = 0)

    @Test
    fun fallsBackToDefaultsForNullsUnknownEnumsAndMissingNullables() {
        val item = ServiceJson.decodeFromString<Item>("""{"name":"A","state":"Sleeping","count":null}""")
        assertEquals(Item("A", State.Ready, overview = null, count = 0), item)
    }
}
