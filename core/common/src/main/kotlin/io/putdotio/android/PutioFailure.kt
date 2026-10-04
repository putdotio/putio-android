package io.putdotio.android

import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioConfigurationException
import io.putdotio.sdk.errors.PutioException
import io.putdotio.sdk.errors.PutioOperationErrorReason
import io.putdotio.sdk.errors.PutioOperationException
import io.putdotio.sdk.errors.PutioSerializationException
import io.putdotio.sdk.errors.PutioTransportException

/**
 * What a Files-family surface explains when it fails: a [PutioFailure], or a Files navigation the
 * app itself refused. It sits beside [PutioFailure] because a sealed type's cases must share its
 * module, and transfers and both apps carry it.
 */
public sealed interface FilesFailure {
    public val cause: Throwable

    public data object NavigationBlocked : FilesFailure {
        override val cause: IllegalStateException = IllegalStateException("Files navigation was rejected")
    }
}

/** A put.io operation that did not complete, in the taxonomy every domain shares. */
public sealed interface PutioFailure : FilesFailure {
    public data class AuthenticationRequired(
        override val cause: PutioException,
    ) : PutioFailure

    public data class AccessDenied(
        override val cause: PutioException,
    ) : PutioFailure

    public data class RateLimited(
        override val cause: PutioException,
    ) : PutioFailure

    public data class ServerUnavailable(
        val statusCode: Int,
        override val cause: PutioException,
    ) : PutioFailure

    public data class ApiRejected(
        val statusCode: Int,
        val errorType: String?,
        override val cause: PutioException,
        val httpStatusCode: Int = statusCode,
    ) : PutioFailure

    /** Any cause: playback also reports a failed media stream or a timed-out position write here. */
    public data class NetworkUnavailable(
        override val cause: Throwable,
    ) : PutioFailure

    public data class InvalidResponse(
        override val cause: PutioException,
    ) : PutioFailure

    public data class Misconfigured(
        override val cause: PutioException,
    ) : PutioFailure

    public data class Unexpected(
        override val cause: Throwable,
    ) : PutioFailure
}

/** put.io's own reason for a refused request; the surface's copy applies when it is null. */
public val PutioFailure.apiReason: String?
    get() = (this as? PutioFailure.ApiRejected)?.cause?.apiRejectionReason()

// Mirrors PutioAuthSessionGateway.isAuthoritativeAuthRejection: a contract-derived
// 401/403 reason is an auth verdict even when the underlying error is not an API
// exception, and the wrapper chain is walked with a cycle guard. The envelope's
// status code classifies the API error; ApiRejected keeps the HTTP status beside it.
public fun PutioException.toPutioFailure(): PutioFailure {
    var current: PutioException = this
    val visited = mutableSetOf<PutioException>()
    while (current is PutioOperationException && visited.add(current)) {
        current.reasonFailure(context = this)?.let { return it }
        current = current.underlyingError
    }
    return current.leafFailure(context = this)
}

/** The API error under the SDK's operation wrappers and causes, if any; cycles end the walk. */
public fun Throwable.findPutioApiException(): PutioApiException? {
    var current: Throwable? = this
    val visited = mutableSetOf<Throwable>()
    while (current != null && visited.add(current)) {
        if (current is PutioApiException) return current
        current = if (current is PutioOperationException) current.underlyingError else current.cause
    }
    return null
}

private fun PutioOperationException.reasonFailure(context: PutioException): PutioFailure? =
    when ((reason as? PutioOperationErrorReason.StatusCode)?.statusCode) {
        HTTP_UNAUTHORIZED -> PutioFailure.AuthenticationRequired(context)
        HTTP_FORBIDDEN -> PutioFailure.AccessDenied(context)
        else -> null
    }

private fun PutioException.leafFailure(context: PutioException): PutioFailure =
    when (this) {
        is PutioApiException ->
            when (statusCode) {
                HTTP_UNAUTHORIZED -> PutioFailure.AuthenticationRequired(context)
                HTTP_FORBIDDEN -> PutioFailure.AccessDenied(context)
                HTTP_TOO_MANY_REQUESTS -> PutioFailure.RateLimited(context)
                in HTTP_SERVER_ERROR_RANGE -> PutioFailure.ServerUnavailable(statusCode, context)
                else -> PutioFailure.ApiRejected(statusCode, errorType, context, httpStatusCode)
            }

        is PutioTransportException -> PutioFailure.NetworkUnavailable(context)
        is PutioSerializationException -> PutioFailure.InvalidResponse(context)
        is PutioConfigurationException -> PutioFailure.Misconfigured(context)
        is PutioOperationException -> PutioFailure.Unexpected(context)
    }

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_TOO_MANY_REQUESTS = 429
private val HTTP_SERVER_ERROR_RANGE = HTTP_SERVER_ERROR_START..HTTP_SERVER_ERROR_END
private const val HTTP_SERVER_ERROR_START = 500
private const val HTTP_SERVER_ERROR_END = 599
