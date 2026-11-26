package io.putdotio.android

internal fun Throwable.apiRejectionReason(): String? =
    findPutioApiException()
        ?.takeIf { api -> listOf(api.statusCode, api.httpStatusCode).all(::isShownStatus) }
        ?.errorMessage
        ?.let(::displayableApiReason)

private fun isShownStatus(status: Int): Boolean =
    status in HTTP_CLIENT_ERROR_START..HTTP_CLIENT_ERROR_END && status !in APP_EXPLAINED_STATUSES

public fun displayableApiReason(raw: String): String? {
    val text = WHITESPACE.replace(raw, " ").trim()
    return text.takeUnless {
        it.isEmpty() ||
            it.length > MAX_REASON_LENGTH ||
            ERROR_CODE.matches(it) ||
            URL_MENTION.containsMatchIn(it) ||
            CREDENTIAL_MENTION.containsMatchIn(it) ||
            it.contains(REDACTED_MARKER)
    }
}

private const val HTTP_CLIENT_ERROR_START = 400
private const val HTTP_CLIENT_ERROR_END = 499
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_REQUEST_TIMEOUT = 408
private const val HTTP_TOO_MANY_REQUESTS = 429
private val APP_EXPLAINED_STATUSES =
    setOf(HTTP_UNAUTHORIZED, HTTP_FORBIDDEN, HTTP_NOT_FOUND, HTTP_REQUEST_TIMEOUT, HTTP_TOO_MANY_REQUESTS)
private const val MAX_REASON_LENGTH = 300
private const val REDACTED_MARKER = "REDACTED"
private val WHITESPACE = Regex("""\s+""")
private val ERROR_CODE = Regex("""[A-Z0-9_-]+|\S*_\S*""")
private val URL_MENTION = Regex(
    """//|\\/|\bmailto:|\bwww\.|\burls?\b|\b[a-z0-9-]+(\.[a-z0-9-]+)+(:\d+)?/""",
    RegexOption.IGNORE_CASE,
)
private val CREDENTIAL_MENTION = Regex(
    """(?<![a-z])(bearer|authorization|tokens?|secrets?|passwords?|credentials?|api[_ -]?keys?)(?![a-z])""",
    RegexOption.IGNORE_CASE,
)
