package com.skeksik.current.core.network

import io.ktor.http.Url
import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.parseUrl

/**
 * Hides secrets in anything the app logs or puts in a problem report (ADR 0015). It redacts too much rather than too little: values
 * go, parameter names stay. Ids of users, items and sessions are masked too, so a route stays readable (`/Users/…/Items/…`) but names
 * nothing on the server.
 */
object Redaction {
    const val PLACEHOLDER = "***"

    /** Stands for an id-like path segment or query value. */
    const val ID_PLACEHOLDER = "…"

    /**
     * A GUID (32 hex digits, with or without dashes, as Jellyfin writes user, item and session ids) or any other run of 16 or more hex
     * digits (hashes). Short numbers such as Seerr's movie ids and route words stay.
     */
    private val idLike = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|[0-9a-fA-F]{16,}")

    fun isIdLike(text: String): Boolean = idLike.matches(text)

    /**
     * Query parameters, in any case, whose values are secrets. Jellyfin media URLs carry `ApiKey`, Stash stream URLs `apikey` (its
     * admin key), and Quick Connect a `secret` and a `code`.
     */
    val secretParameters: Set<String> = setOf("apikey", "api_key", "token", "access_token", "accesstoken", "secret", "password", "pw", "code")

    fun isSecretParameter(name: String): Boolean = name.lowercase() in secretParameters

    /**
     * [url]'s path and query with secret values hidden and ids masked; no scheme, host or port, because logs name the server by its key
     * instead. For logs only: never build a request from it.
     */
    fun pathAndQuery(url: Url): String = buildString {
        append(url.encodedPath.ifEmpty { "/" }.split('/').joinToString("/") { maskId(it) })
        val query = redactQuery(url.encodedQuery)
        if (query.isNotEmpty()) append('?').append(query)
    }

    /**
     * A whole URL with credentials and secret query values hidden, for URLs that leave the app's own logging (stream URLs handed to an
     * engine) and are logged. Keeps the host, masks ids like [pathAndQuery]. Text that is not a URL is hidden entirely. For logs only.
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
            when {
                '=' !in pair -> pair
                isSecretParameter(decodedName) -> "$name=$PLACEHOLDER"
                // A list of ids, such as Ids=a,b, masks each one.
                else -> "$name=" + pair.substringAfter('=').split(",", "%2C", "%2c").joinToString(",") { maskId(it) }
            }
        }

    /** [encoded] (a path segment or one query value, URL-encoded) or the placeholder if it is id-like. */
    private fun maskId(encoded: String): String {
        val decoded = runCatching { encoded.decodeURLQueryComponent() }.getOrDefault(encoded)
        return if (isIdLike(decoded)) ID_PLACEHOLDER else encoded
    }

    private fun Url.hostWithPortIfSpecified(): String =
        if (specifiedPort == 0 || specifiedPort == protocol.defaultPort) host else "$host:$specifiedPort"
}
