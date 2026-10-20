package com.skeksik.current.core.network

import com.skeksik.current.core.model.ServerRef
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Talks to one configured server. Paths are relative to the server's current address, with or without a leading slash
 * (`get("System/Info/Public")`), so the client follows the server when its address changes ([ServerAddressSource]).
 *
 * Every call goes through [request], which turns failures into [ServiceException]. Responses outside 2xx fail; redirects are not
 * followed, so credentials never go to another host.
 */
class ServerClient internal constructor(
    val server: ServerRef,
    private val address: ServerAddressSource,
    private val http: HttpClient,
    private val onUnauthorized: (() -> Unit)?,
) : AutoCloseable {
    /** Runs [block] on this server's client. Throws [ServiceException] for request failures; cancellation and bugs pass through. */
    suspend fun <T> request(block: suspend HttpClient.() -> T): T {
        // The one wait for the source; the requests in [block] go to this address ([ChosenAddress]), and a failure reports it.
        val used = address.awaitCurrent() ?: throw ServiceException(server, ServiceErrorKind.Unreachable)
        try {
            return withContext(ChosenAddress(address, used)) { http.block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failure = serviceExceptionFor(server, e) ?: throw e
            // A timeout counts too: a dead address often hangs instead of refusing, and no network signal catches every change.
            val noAnswer = failure.kind == ServiceErrorKind.Unreachable || failure.kind == ServiceErrorKind.Timeout
            if (noAnswer) address.reportUnreachable(used)
            if (failure.kind == ServiceErrorKind.Unauthorized) onUnauthorized?.invoke()
            throw failure
        }
    }

    /** Closes this server's client; the shared engine stays open. */
    override fun close() = http.close()
}

/**
 * The address [ServerClient.request] chose for the requests in its block, so [ServerRequests] uses it instead of waiting again. It
 * names its [source], so a request to another server made inside the block never picks it up.
 */
internal class ChosenAddress(val source: ServerAddressSource, val url: ServerUrl) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ChosenAddress>
}

/** Makes a [ServerClient] per configured server, all sharing the app's one [HttpClient] and its engine. */
class ServerClients(
    private val base: HttpClient,
    private val logger: NetworkLogger = NetworkLogger.None,
    private val requestTimeout: Duration = 30.seconds,
) {
    /** [onUnauthorized] runs whenever the server answers 401, so the data layer can sign that server out (a revoked token). */
    fun create(
        server: ServerRef,
        address: ServerAddressSource,
        auth: RequestAuth = RequestAuth.None,
        onUnauthorized: (() -> Unit)? = null,
    ): ServerClient {
        val http = base.config {
            expectSuccess = true
            followRedirects = false
            install(HttpTimeout) { requestTimeoutMillis = requestTimeout.inWholeMilliseconds }
            install(ServerRequests) {
                this.server = server
                this.address = address
                this.auth = auth
            }
            install(RequestLog) {
                this.server = server
                this.logger = this@ServerClients.logger
            }
        }
        return ServerClient(server, address, http, onUnauthorized)
    }
}
