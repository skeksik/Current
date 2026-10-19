package com.skeksik.current.core.network

import io.ktor.client.request.HttpRequestBuilder

/**
 * Adds a service's credentials to every request to one server. Each data layer implements its service's scheme (Jellyfin's
 * `MediaBrowser` header, Stash's `ApiKey`); core knows none of them. Runs after the request is moved onto the server's current address.
 */
fun interface RequestAuth {
    suspend fun authorize(request: HttpRequestBuilder)

    companion object {
        /** For servers or calls that need no credentials, such as a public info endpoint. */
        val None = RequestAuth { }
    }
}
