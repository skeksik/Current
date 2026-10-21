package com.skeksik.current.core.network

import com.skeksik.current.core.model.ServerRef

/**
 * Receives one entry per request to a server. Entries never hold headers, bodies or hosts, and secret query values are already
 * redacted ([Redaction]), so an implementation may write them anywhere, including a problem report.
 */
fun interface NetworkLogger {
    fun log(entry: RequestLogEntry)

    /** A bug that ended background work: [what] happened, and the error's class name, never its message (ADR 0015). */
    fun bug(what: String, error: Throwable) {}

    companion object {
        val None = NetworkLogger { }
    }
}

/**
 * [status] is null when no response arrived. [cancelled] tells why: true when the caller stopped waiting (a roaming check that no
 * longer needs a lower-ranked probe), false when the request failed (unreachable or timed out).
 */
data class RequestLogEntry(
    val server: ServerRef,
    val method: String,
    /** "http" or "https", which tells apart the probes of one address; never the host or port. */
    val scheme: String,
    /** Path and query, redacted. */
    val path: String,
    val status: Int?,
    val durationMillis: Long,
    val cancelled: Boolean = false,
) {
    override fun toString(): String =
        "${server.service.value}/${server.key} $method $scheme://…$path ${status ?: if (cancelled) "cancelled" else "no response"} (${durationMillis} ms)"
}
