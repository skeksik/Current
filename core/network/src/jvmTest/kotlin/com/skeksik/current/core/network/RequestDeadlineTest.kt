package com.skeksik.current.core.network

import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServiceType
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineCapability
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.utils.io.InternalAPI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** The request timeout must hold while the engine's thread is blocked, as in a DNS lookup on Android (docs/platform-notes.md). */
class RequestDeadlineTest {
    private val server = ServerRef(ServiceType.jellyfin, "server-1")
    private val address = FixedServerAddress(ServerUrl.parse("https://media.example.com")!!)

    /** Blocks its thread until released, ignoring coroutine cancellation, as `InetAddress` does; then fails like an unknown host. */
    private class BlockingEngine : HttpClientEngineBase("blocking") {
        val release = CountDownLatch(1)
        override val config = HttpClientEngineConfig()
        override val supportedCapabilities: Set<HttpClientEngineCapability<*>> = setOf(HttpTimeoutCapability)

        @InternalAPI
        override suspend fun execute(data: HttpRequestData): HttpResponseData {
            release.await(10, TimeUnit.SECONDS)
            throw java.net.UnknownHostException("blocked")
        }
    }

    private val engine = BlockingEngine()

    @AfterTest
    fun releaseEngine() {
        engine.release.countDown()
        engine.close()
    }

    private fun client(requestTimeout: Duration) =
        ServerClients(createHttpClient(engine), requestTimeout = requestTimeout).create(server, address)

    private suspend fun timed(block: suspend () -> Unit): Duration {
        val start = TimeSource.Monotonic.markNow()
        block()
        return start.elapsedNow()
    }

    @Test
    fun theClientTimeoutHoldsWhileTheEngineIsBlocked() = runBlocking {
        client(200.milliseconds).use { client ->
            val elapsed = timed {
                val failure = assertFailsWith<ServiceException> { client.request { get("System/Info/Public") } }
                assertEquals(ServiceErrorKind.Timeout, failure.kind)
            }
            assertTrue(elapsed < 2_000.milliseconds, "returned after $elapsed")
        }
    }

    @Test
    fun aShorterRequestTimeoutWins() = runBlocking {
        client(30_000.milliseconds).use { client ->
            val elapsed = timed {
                val failure = assertFailsWith<ServiceException> {
                    client.request { get("System/Info/Public") { timeout { requestTimeoutMillis = 200 } } }
                }
                assertEquals(ServiceErrorKind.Timeout, failure.kind)
            }
            assertTrue(elapsed < 2_000.milliseconds, "returned after $elapsed")
        }
    }

    @Test
    fun anOuterCancellationStaysACancellation() = runBlocking {
        client(30_000.milliseconds).use { client ->
            val elapsed = timed {
                assertFailsWith<TimeoutCancellationException> { withTimeout(200) { client.request { get("System/Info/Public") } } }
            }
            assertTrue(elapsed < 2_000.milliseconds, "returned after $elapsed")
        }
    }
}
