package com.skeksik.current.core.network

import io.ktor.http.Url
import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.parseUrl

/**
 * Hides secrets in anything the app logs or puts in a problem report (ADR 0015). It redacts too much rather than too little: values
 * go, parameter names stay.
 */
object Redaction {
    const val PLACEHOLDER = "***"

    /**
     * Query parameters, in any case, whose values are secrets. Jellyfin media URLs carry `ApiKey`, Stash stream URLs `apikey` (its
     * admin key), and Quick Connect a `secret` and a `code`.
     */
    val secretParameters: Set<String> = setOf("apikey", "api_key", "token", "access_token", "accesstoken", "secret", "password", "pw", "code")

    fun isSecretParameter(name: String): Boolean = name.lowercase() in secretParameters

    /** [url]'s path and query with secret values hidden; no scheme, host or port, because logs name the server by its key instead. */
    fun pathAndQuery(url: Url): String = buildString {
        append(url.encodedPath.ifEmpty { "/" })
        val query = redactQuery(url.encodedQuery)
        if (query.isNotEmpty()) append('?').append(query)
    }

    /**
     * A whole URL with credentials and secret query values hidden, for URLs that leave the app's own logging (stream URLs handed to an
     * engine). Keeps the host. Text that is not a URL is hidden entirely.
     */
    fun url(text: String): String {
        val url = parseUrl(text) ?: return PLACEHOLDER
        return buildString {
            append(url.protocol.name).append("://")
            if (url.user != null || url.password != null) append(PLACEHOLDER).append('@')
            append(url.hostWithPortIfSpecified())
            append(pathAndQuery(url))
        }
    }

    private fun redactQuery(encodedQuery: String): String = encodedQuery
        .split('&')
        .filter { it.isNotEmpty() }
        .joinToString("&") { pair ->
            val name = pair.substringBefore('=')
            val decodedName = runCatching { name.decodeURLQueryComponent() }.getOrDefault(name)
            if ('=' in pair && isSecretParameter(decodedName)) "$name=$PLACEHOLDER" else pair
        }

    private fun Url.hostWithPortIfSpecified(): String =
        if (specifiedPort == 0 || specifiedPort == protocol.defaultPort) host else "$host:$specifiedPort"
}
