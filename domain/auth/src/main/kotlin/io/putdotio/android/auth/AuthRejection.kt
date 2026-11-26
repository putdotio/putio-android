package io.putdotio.android.auth

import io.putdotio.android.findPutioApiException
import io.putdotio.sdk.errors.PutioOperationErrorReason
import io.putdotio.sdk.errors.PutioOperationException

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
