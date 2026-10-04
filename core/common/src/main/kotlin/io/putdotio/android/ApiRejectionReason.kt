package io.putdotio.android

/**
 * put.io's own explanation of a refused request, as web shows it: the SDK's `errorMessage` of a
 * 4xx the app has no copy of its own for. 401, 403, 408 and 429, 5xx, transport and parse
 * failures have none. Text that is a bare error code, names a URL, mentions a credential, or
 * carries the SDK's redaction marker is not shown either, so callers fall back to their own copy.
 */
internal fun Throwable.apiRejectionReason(): String? =
    findPutioApiException()
        ?.takeIf { api -> listOf(api.statusCode, api.httpStatusCode).all(::isShownStatus) }
        ?.errorMessage
        ?.let(::displayableApiReason)

private fun isShownStatus(status: Int): Boolean =
    status in HTTP_CLIENT_ERROR_START..HTTP_CLIENT_ERROR_END && status !in APP_EXPLAINED_STATUSES

/** [raw], put.io's free text, if the app may show it under the rules above; otherwise null. */
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
private const val HTTP_REQUEST_TIMEOUT = 408
private const val HTTP_TOO_MANY_REQUESTS = 429
private val APP_EXPLAINED_STATUSES =
    setOf(HTTP_UNAUTHORIZED, HTTP_FORBIDDEN, HTTP_REQUEST_TIMEOUT, HTTP_TOO_MANY_REQUESTS)
private const val MAX_REASON_LENGTH = 300
private const val REDACTED_MARKER = "REDACTED"
private val WHITESPACE = Regex("""\s+""")
// FILE_NOT_FOUND, FILE-NOT-FOUND, file_not_found: one all-capitals or snake_case word.
private val ERROR_CODE = Regex("""[A-Z0-9_-]+|\S*_\S*""")
// Schemes, mailto, scheme-relative and JSON-escaped links, www hosts, and host[:port]/path forms.
private val URL_MENTION = Regex(
    """//|\\/|\bmailto:|\bwww\.|\burls?\b|\b[a-z0-9-]+(\.[a-z0-9-]+)+(:\d+)?/""",
    RegexOption.IGNORE_CASE,
)
// Letter boundaries so access_token matches and "tokenized" does not.
private val CREDENTIAL_MENTION = Regex(
    """(?<![a-z])(bearer|authorization|tokens?|secrets?|passwords?|credentials?|api[_ -]?keys?)(?![a-z])""",
    RegexOption.IGNORE_CASE,
)
