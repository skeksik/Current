package com.skeksik.current.core.network

import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal class RequestDeadlineConfig {
    /** Used when the request sets no request timeout of its own. */
    var requestTimeout: Duration = 30.seconds
}

/**
 * Ends a request at its request timeout (the request's `timeout { requestTimeoutMillis }`, else [RequestDeadlineConfig.requestTimeout])
 * with [HttpRequestTimeoutException], even while the engine is blocked. `HttpTimeout` alone only cancels the engine's call, and the
 * caller then waits until a blocking engine returns: on Android a DNS lookup or one connect per address can run past the timeout
 * (docs/platform-notes.md, "Request timeouts on blocking engines"). Here the caller stops waiting; the blocked thread finishes later.
 */
internal val RequestDeadline = createClientPlugin("RequestDeadline", ::RequestDeadlineConfig) {
    val default = pluginConfig.requestTimeout
    on(Send) { request ->
        val millis = request.getCapabilityOrNull(HttpTimeoutCapability)?.requestTimeoutMillis ?: default.inWholeMilliseconds
        if (millis == HttpTimeoutConfig.INFINITE_TIMEOUT_MS) return@on proceed(request)
        // withTimeoutOrNull gives null only for its own deadline, so an outer cancellation still propagates.
        withTimeoutOrNull(millis.milliseconds) { proceed(request) } ?: throw HttpRequestTimeoutException(request)
    }
}
