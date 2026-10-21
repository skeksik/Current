package com.skeksik.current.core.network

import com.skeksik.current.core.database.DeviceIdentity
import com.skeksik.current.core.database.InMemoryKeyValueStore
import com.skeksik.current.core.database.InMemorySecretStore
import com.skeksik.current.core.database.KeyValueStore
import com.skeksik.current.core.database.SecretStore
import com.skeksik.current.core.database.databaseModule
import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServiceType
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/** What the app's request log keeps, end to end: no host, port, credentials, header or body, whatever the request carried. */
class RequestLogRedactionTest {
    private val server = ServerRef(ServiceType.jellyfin, "server-1")
    private val secrets = listOf("media.example.com", "8920", "hunter2", "secret-key", "quick-secret", "token-value", "MediaBrowser", "body-text")

    @Test
    fun linesHoldNoHostOrSecret() = runBlocking {
        val buffer = RequestLogBuffer()
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("Fail")) respond("body-text secret-key", HttpStatusCode.InternalServerError)
            else respond("body-text")
        }
        val address = FixedServerAddress(ServerUrl.parse("https://media.example.com:8920/jellyfin")!!)
        val auth = RequestAuth {
            it.header(HttpHeaders.Authorization, "MediaBrowser Token=\"token-value\"")
            it.url.parameters.append("ApiKey", "secret-key")
        }
        ServerClients(createHttpClient(engine), buffer).create(server, address, auth).use { client ->
            client.request { get("QuickConnect/Connect?Secret=quick-secret&pw=hunter2") }
            assertFailsWith<ServiceException> { client.request { get("Items/Fail?token=token-value") } }
        }
        val lines = buffer.lines()
        assertEquals(2, lines.size, lines.toString())
        assertTrue(lines.all { it.startsWith("jellyfin/server-1 GET https://…/jellyfin/") }, lines.toString())
        for (secret in secrets) assertFalse(lines.any { secret in it }, "$secret in $lines")
    }

    @Test
    fun theAppBindsTheBufferAsLoggerAndHistory() {
        val stores = module {
            single<KeyValueStore> { InMemoryKeyValueStore() }
            single<KeyValueStore>(named("local")) { InMemoryKeyValueStore() }
            single<SecretStore> { InMemorySecretStore() }
        }
        val app = koinApplication { modules(databaseModule, networkModule, stores) }
        try {
            val koin = app.koin
            koin.get<DeviceIdentity>()
            val logger = koin.get<NetworkLogger>()
            assertIs<RequestLogBuffer>(logger)
            assertSame(logger, koin.get<RequestLogHistory>())
            assertTrue(koin.get<PlatformInfo>().describe().startsWith("Desktop "))
        } finally {
            app.close()
        }
    }
}
