package com.skeksik.current.core.network

import com.skeksik.current.core.model.ServerRef
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.serialization.ContentConvertException
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException

/** Why a request to a server failed, as far as the app cares. Service-specific reasons (Seerr's messages) are the data layer's. */
enum class ServiceErrorKind {
    /** No connection: offline, wrong address, server down, TLS failure, network access denied to the app, or no address known to work. */
    Unreachable,

    /** Connected, but no complete answer in time. */
    Timeout,

    /** 401: signed out, or the token or key was revoked. */
    Unauthorized,

    /** 403: signed in, not allowed. */
    Forbidden,

    /** 404. */
    NotFound,

    /** Any other 4xx: the server refused the request as sent. */
    Rejected,

    /** 5xx. */
    ServerError,

    /** An answer the app cannot use: a body that does not decode, or a redirect (never followed, so credentials stay on the server). */
    InvalidResponse,
}

/**
 * A request to [server] failed. Repositories throw it; ViewModels turn it into UI state, so it never reaches Swift or JavaScript.
 *
 * The message names only the server's opaque key, the kind and the status. The original exception is not kept as the cause: Ktor's
 * exceptions carry the full request URL with its query secrets, and decoding errors quote the body (ADR 0015). [detail] keeps its
 * class name for problem reports.
 */
class ServiceException(
    val server: ServerRef,
    val kind: ServiceErrorKind,
    val status: Int? = null,
    val detail: String? = null,
) : Exception(
    buildString {
        append(server.service.value).append(" server ").append(server.key).append(": ").append(kind.name)
        if (status != null) append(" (HTTP ").append(status).append(')')
        if (detail != null) append(" [").append(detail).append(']')
    },
)

/**
 * The [ServiceException] for a failed request to [server], or null when [error] is not a request failure: cancellation, or a bug
 * in the calling code, which must surface unchanged.
 */
internal fun serviceExceptionFor(server: ServerRef, error: Throwable): ServiceException? {
    val detail = error::class.simpleName
    return when (error) {
        is ServiceException -> error
        is CancellationException -> null
        is HttpRequestTimeoutException -> ServiceException(server, ServiceErrorKind.Timeout, detail = detail)
        is ResponseException -> {
            val status = error.response.status.value
            ServiceException(server, kindFor(status), status, detail)
        }
        is IOException -> ServiceException(server, ServiceErrorKind.Unreachable, detail = detail)
        is SerializationException, is ContentConvertException, is NoTransformationFoundException ->
            ServiceException(server, ServiceErrorKind.InvalidResponse, detail = detail)
        // A connection failure the platform reports outside IOException: Android refusing network access to the app, a browser's
        // failed fetch. The server cannot be reached, which must not end a roaming loop as an unexpected exception would. Other errors
        // of the same classes (a file without a grant, any other Error) stay bugs.
        else -> if (isPlatformUnreachable(error)) ServiceException(server, ServiceErrorKind.Unreachable, detail = detail) else null
    }
}

private fun kindFor(status: Int): ServiceErrorKind = when {
    status == 401 -> ServiceErrorKind.Unauthorized
    status == 403 -> ServiceErrorKind.Forbidden
    status == 404 -> ServiceErrorKind.NotFound
    status in 400..499 -> ServiceErrorKind.Rejected
    status >= 500 -> ServiceErrorKind.ServerError
    else -> ServiceErrorKind.InvalidResponse
}

/**
 * Whether [error] is a connection failure this platform's engine reports outside `IOException`: Android refusing network access to an
 * app without `INTERNET`, or the browser engine's failed fetch.
 */
internal expect fun isPlatformUnreachable(error: Throwable): Boolean

/**
 * Ktor's JS engine wraps every failed fetch (offline, refused, an unknown name, a dropped connection) in `Error("Fail to fetch")`,
 * whose cause is the browser's `TypeError` (docs/platform-notes.md, "Roaming in browsers"). Only that exact message counts.
 */
internal fun isKtorFetchFailure(message: String?): Boolean = message == "Fail to fetch"

/** Android's message for a socket refused for want of `INTERNET`: "Permission denied (missing INTERNET permission?)". */
internal fun isMissingInternetPermission(message: String?): Boolean = message.orEmpty().contains("INTERNET permission")
