package com.skeksik.current.core.network

import com.skeksik.current.core.model.ServerRef
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.URLBuilder
import kotlin.time.TimeSource
import kotlinx.coroutines.currentCoroutineContext

internal class ServerRequestsConfig {
    lateinit var server: ServerRef
    lateinit var address: ServerAddressSource
    var auth: RequestAuth = RequestAuth.None
}

/** Moves every request onto the server's current address, then adds the service's credentials. */
internal val ServerRequests = createClientPlugin("ServerRequests", ::ServerRequestsConfig) {
    val server = pluginConfig.server
    val address = pluginConfig.address
    val auth = pluginConfig.auth
    onRequest { request, _ ->
        // Outside ServerClient.request (a raw call), the plugin waits for the source itself.
        val base = currentCoroutineContext()[ChosenAddress]?.url ?: address.awaitCurrent()
            ?: throw ServiceException(server, ServiceErrorKind.Unreachable)
        request.url.rebaseOnto(base)
        auth.authorize(request)
    }
}

/**
 * Puts a request's path below [base]: scheme, host and port come from [base], and the path is appended to its prefix whether it was
 * written with a leading slash or without. Query parameters stay.
 */
internal fun URLBuilder.rebaseOnto(base: ServerUrl) {
    val path = encodedPathSegments.dropWhile { it.isEmpty() }
    protocol = base.url.protocol
    host = base.url.host
    port = base.url.specifiedPort
    user = null
    password = null
    encodedPathSegments = base.url.rawSegments + path
}

internal class RequestLogConfig {
    lateinit var server: ServerRef
    var logger: NetworkLogger = NetworkLogger.None
}

/** Logs method, redacted path, status and duration of every request, never headers or bodies (ADR 0015). */
internal val RequestLog = createClientPlugin("RequestLog", ::RequestLogConfig) {
    val server = pluginConfig.server
    val logger = pluginConfig.logger
    on(Send) { request ->
        val start = TimeSource.Monotonic.markNow()
        var status: Int? = null
        try {
            proceed(request).also { status = it.response.status.value }
        } finally {
            logger.log(
                RequestLogEntry(
                    server = server,
                    method = request.method.value,
                    path = Redaction.pathAndQuery(request.url.build()),
                    status = status,
                    durationMillis = start.elapsedNow().inWholeMilliseconds,
                ),
            )
        }
    }
}
