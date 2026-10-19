package com.skeksik.current.core.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** The HTTP client the service repositories share, on this platform's engine. */
fun createHttpClient(): HttpClient = HttpClient(platformEngine) { installDefaults() }

/** The same client on a given [engine] (tests); the engine is not closed with the client. */
internal fun createHttpClient(engine: HttpClientEngine): HttpClient = HttpClient(engine) { installDefaults() }

/** Servers add fields in newer versions; unknown ones must not fail decoding. */
internal val ServiceJson = Json { ignoreUnknownKeys = true }

private fun HttpClientConfig<*>.installDefaults() {
    install(ContentNegotiation) { json(ServiceJson) }
}

/** Android: HttpURLConnection; desktop: the JDK's HttpClient; Apple: NSURLSession; web: fetch. */
internal expect val platformEngine: HttpClientEngineFactory<*>
