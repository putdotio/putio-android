package io.putdotio.android.auth

import io.putdotio.android.findPutioApiException
import io.putdotio.sdk.errors.PutioOperationErrorReason
import io.putdotio.sdk.errors.PutioOperationException

/**
 * A 401/403 verdict anywhere in the wrapper chain is an authoritative rejection
 * of the session; every other failure is a retryable unknown.
 */
public fun Throwable.isAuthoritativeAuthRejection(): Boolean {
    val operationReason = (this as? PutioOperationException)?.reason
    if (operationReason is PutioOperationErrorReason.StatusCode && operationReason.statusCode.isAuthRejectionStatus()) {
        return true
    }
    return findPutioApiException()?.statusCode?.isAuthRejectionStatus() == true
}

private fun Int.isAuthRejectionStatus(): Boolean = this == HTTP_UNAUTHORIZED || this == HTTP_FORBIDDEN

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
