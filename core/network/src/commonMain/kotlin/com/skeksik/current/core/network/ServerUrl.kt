package com.skeksik.current.core.network

import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.parseUrl

/**
 * One address of a server: http or https, a host, an optional port and an optional path prefix (a server behind a reverse proxy at
 * `/jellyfin`). No credentials, query or fragment. Requests resolve their paths below it ([ServerClient]).
 */
class ServerUrl private constructor(internal val url: Url) {
    val isHttps: Boolean get() = url.protocol == URLProtocol.HTTPS

    override fun equals(other: Any?): Boolean = other is ServerUrl && other.url == url

    override fun hashCode(): Int = url.hashCode()

    /** The address as the user entered it, normalised. Not for logs: they name the server by its key (ADR 0015). */
    override fun toString(): String = url.toString()

    companion object {
        /**
         * Parses an address with its scheme (`https://host`, `http://192.168.1.10:8096/jellyfin`), or returns null. Trailing and doubled
         * slashes in the path are dropped. Guessing a missing scheme is server setup's job, which can try both.
         */
        fun parse(text: String): ServerUrl? {
            val trimmed = text.trim()
            if (!trimmed.contains("://")) return null
            val url = parseUrl(trimmed) ?: return null
            if (url.protocol != URLProtocol.HTTP && url.protocol != URLProtocol.HTTPS) return null
            if (url.host.isBlank() || url.user != null || url.password != null) return null
            if ('?' in trimmed || '#' in trimmed) return null
            val normalised = URLBuilder(protocol = url.protocol, host = url.host, port = url.specifiedPort).apply {
                encodedPathSegments = url.rawSegments.filter { it.isNotEmpty() }
            }.build()
            return ServerUrl(normalised)
        }
    }
}
