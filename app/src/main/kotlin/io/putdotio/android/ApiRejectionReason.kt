package io.putdotio.android

import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioOperationException

/**
 * put.io's own explanation of a refused request, as web shows it: the SDK's `errorMessage` of a
 * 4xx the app has no copy of its own for. 401, 403, 408 and 429, 5xx, transport and parse
 * failures have none. Text that is a bare error code, names a URL, or carries the SDK's
 * redaction marker is not shown either, so callers fall back to their own copy.
 */
internal fun Throwable.apiRejectionReason(): String? =
    findApiException()
        ?.takeIf { api -> listOf(api.statusCode, api.httpStatusCode).all(::isShownStatus) }
        ?.errorMessage
        ?.let(::displayableReason)

private fun isShownStatus(status: Int): Boolean =
    status in HTTP_CLIENT_ERROR_START..HTTP_CLIENT_ERROR_END && status !in APP_EXPLAINED_STATUSES

private fun displayableReason(raw: String): String? {
    val text = WHITESPACE.replace(raw, " ").trim()
    return text.takeUnless {
        it.isEmpty() ||
            it.length > MAX_REASON_LENGTH ||
            ERROR_CODE.matches(it) ||
            URL_MENTION.containsMatchIn(it) ||
            it.contains(REDACTED_MARKER)
    }
}

private fun Throwable.findApiException(): PutioApiException? {
    var current: Throwable? = this
    val visited = mutableSetOf<Throwable>()
    while (current != null && visited.add(current)) {
        if (current is PutioApiException) return current
        current = if (current is PutioOperationException) current.underlyingError else current.cause
    }
    return null
}

private const val HTTP_CLIENT_ERROR_START = 400
private const val HTTP_CLIENT_ERROR_END = 499
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_REQUEST_TIMEOUT = 408
private const val HTTP_TOO_MANY_REQUESTS = 429
private val APP_EXPLAINED_STATUSES =
    setOf(HTTP_UNAUTHORIZED, HTTP_FORBIDDEN, HTTP_REQUEST_TIMEOUT, HTTP_TOO_MANY_REQUESTS)
private const val MAX_REASON_LENGTH = 300
private const val REDACTED_MARKER = "REDACTED"
private val WHITESPACE = Regex("""\s+""")
private val ERROR_CODE = Regex("""[A-Z0-9_]+""")
private val URL_MENTION = Regex("""://|\bwww\.|\burls?\b""", RegexOption.IGNORE_CASE)
