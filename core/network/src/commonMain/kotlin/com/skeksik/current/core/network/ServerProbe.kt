package com.skeksik.current.core.network

import com.skeksik.current.core.model.ServerRef
import com.skeksik.current.core.model.ServiceType
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Who answers at an address, read without credentials. */
data class ServerIdentity(
    /** The server's own id, stable across restarts and addresses (Jellyfin's `Id`). */
    val serverId: String,
    val name: String,
    val version: String?,
)

/**
 * Asks an address which server answers there, without credentials (ADR 0016). Each data layer implements it for its service; a
 * service that cannot identify itself without credentials (Stash) has none.
 */
interface ServerProbe {
    val service: ServiceType

    /** The port a typed address without one may be on (`http://host:8096`), or null. */
    val defaultPort: Int?

    /** The longest [identify] waits for an answer; roaming makes requests wait for a check about twice that (ADR 0019). */
    val timeout: Duration get() = 5.seconds

    /** Throws [ServiceException]: unreachable, a redirect, or an answer that is not this service. Logged under [server]. */
    suspend fun identify(server: ServerRef, address: ServerUrl): ServerIdentity
}

/**
 * The addresses to try for what the user typed, best first. With a scheme, only that address. Without one, `https` then `http`, and
 * when no port is given, also `http` on [defaultPort]. Empty when the text is no address at all.
 */
fun ServerUrl.Companion.candidates(text: String, defaultPort: Int?): List<ServerUrl> {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return emptyList()
    if ("://" in trimmed) return listOfNotNull(parse(trimmed))
    val hasPort = trimmed.substringBefore('/').let { hostAndPort ->
        if (hostAndPort.startsWith("[")) hostAndPort.substringAfter("]").startsWith(":") else hostAndPort.contains(':')
    }
    val withDefaultPort = if (hasPort || defaultPort == null) null else {
        val host = trimmed.substringBefore('/')
        val path = trimmed.substring(host.length)
        "http://$host:$defaultPort$path"
    }
    return listOfNotNull(parse("https://$trimmed"), parse("http://$trimmed"), withDefaultPort?.let(::parse)).distinct()
}
