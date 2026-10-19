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

/**
 * Lenient, because servers change between versions: unknown fields are ignored, a null or unknown enum value for a property with a
 * default takes the default, and a missing nullable property is null (Jellyfin omits most nulls). When encoding, null properties are
 * left out; a data layer whose server treats a missing field differently from null (Seerr's settings endpoints) sets them explicitly.
 */
internal val ServiceJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

private fun HttpClientConfig<*>.installDefaults() {
    install(ContentNegotiation) { json(ServiceJson) }
}

/** Android: HttpURLConnection; desktop: the JDK's HttpClient; Apple: NSURLSession; web: fetch. */
internal expect val platformEngine: HttpClientEngineFactory<*>
