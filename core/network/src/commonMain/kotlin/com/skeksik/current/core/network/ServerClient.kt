package com.skeksik.current.core.network

import com.skeksik.current.core.model.ServerRef
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CancellationException
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
        val used = address.current.value
        try {
            return http.block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failure = serviceExceptionFor(server, e) ?: throw e
            if (failure.kind == ServiceErrorKind.Unreachable && used != null) address.reportUnreachable(used)
            if (failure.kind == ServiceErrorKind.Unauthorized) onUnauthorized?.invoke()
            throw failure
        }
    }

    /** Closes this server's client; the shared engine stays open. */
    override fun close() = http.close()
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
            // Send hooks nest in install order, the first outermost. The log goes outside the deadline, so the deadline's own
            // timeout is not seen as the caller's cancellation.
            install(RequestLog) {
                this.server = server
                this.logger = this@ServerClients.logger
            }
            install(RequestDeadline) { requestTimeout = this@ServerClients.requestTimeout }
            install(ServerRequests) {
                this.server = server
                this.address = address
                this.auth = auth
            }
        }
        return ServerClient(server, address, http, onUnauthorized)
    }
}
