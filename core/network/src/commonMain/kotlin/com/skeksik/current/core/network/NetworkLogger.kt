package com.skeksik.current.core.network

import com.skeksik.current.core.model.ServerRef

/**
 * Receives one entry per request to a server. Entries never hold headers, bodies or hosts, and secret query values are already
 * redacted ([Redaction]), so an implementation may write them anywhere, including a problem report.
 */
fun interface NetworkLogger {
    fun log(entry: RequestLogEntry)

    companion object {
        val None = NetworkLogger { }
    }
}

/** [status] is null when no response arrived (unreachable, timed out or cancelled). */
data class RequestLogEntry(
    val server: ServerRef,
    val method: String,
    /** Path and query, redacted. */
    val path: String,
    val status: Int?,
    val durationMillis: Long,
) {
    override fun toString(): String =
        "${server.service.value}/${server.key} $method $path ${status ?: "no response"} (${durationMillis} ms)"
}
