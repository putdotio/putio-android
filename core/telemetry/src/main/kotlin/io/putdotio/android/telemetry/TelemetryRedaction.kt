package io.putdotio.android.telemetry

import java.net.URI
import java.net.URISyntaxException
import java.util.Date

/**
 * The text and value rules every telemetry field goes through, ported from putio-ios
 * `TelemetryRedaction`. Media titles have no detectable shape, which is why the apps report
 * [TelemetryFailure] identifiers instead of error messages.
 */
internal object TelemetryRedaction {
    const val REDACTED: String = "[redacted]"
    const val PATH: String = "[path]"
    const val FILE: String = "[file]"

    /** Strips URLs down to their host, and removes credentials, file paths, and media filenames. */
    fun scrub(text: String): String {
        var result = URL.replace(text) { urlPlaceholder(it.value) }
        result = SCHEME_CREDENTIAL.replace(result, "$1 $REDACTED")
        result = KEYED_SECRET.replace(result, "$1$2$REDACTED")
        result = FILE_PATH.replace(result, PATH)
        result = MEDIA_FILE.replace(result, FILE)
        return result
    }

    /**
     * Scrubs a value of any telemetry shape. A key that names a credential or media loses its
     * value entirely; an object of any other type is dropped rather than serialized through
     * `toString()`.
     */
    fun scrubValue(value: Any?, key: String? = null): Any? =
        when {
            value == null -> null
            key != null && isSensitiveKey(key) -> REDACTED
            value is String -> scrub(value)
            value is Map<*, *> -> scrubMap(value)
            value is Iterable<*> -> value.map { scrubValue(it) }
            value is Array<*> -> value.map { scrubValue(it) }
            value is Number || value is Boolean || value is Date -> value
            else -> REDACTED
        }

    fun scrubMap(map: Map<*, *>): Map<String, Any?> =
        map.entries.associate { (key, value) -> key.toString() to scrubValue(value, key.toString()) }

    /** True for keys whose values are credentials, request payloads, or media identity. */
    fun isSensitiveKey(key: String): Boolean {
        val normalized = key.lowercase().filter(Char::isLetterOrDigit)
        return normalized in SENSITIVE_KEYS || SENSITIVE_KEY_FRAGMENTS.any(normalized::contains)
    }

    private fun urlPlaceholder(url: String): String {
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return "[url]"
        }
        return when {
            uri.scheme.equals("file", ignoreCase = true) -> PATH
            uri.host.isNullOrEmpty() -> "[url]"
            else -> "[url:${uri.host.lowercase()}]"
        }
    }

    private val URL = Regex("""[A-Za-z][A-Za-z0-9+.-]*://[^\s"'<>\]\[)(]+""")
    private val SCHEME_CREDENTIAL = Regex("""(?i)\b(bearer|basic|token|digest)\s+[A-Za-z0-9._~+/=-]{6,}""")
    private val KEYED_SECRET = Regex(
        """(?i)([A-Za-z0-9_.-]*(?:token|secret|passw(?:or)?d|pwd|auth[a-z]*|signature|sig|api[_-]?key|""" +
            """access[_-]?key|cookie|session[_-]?id|credential|otp)[A-Za-z0-9_.-]*)("?\s*[=:]\s*)""" +
            """("[^"]*"|'[^']*'|[^\s&,;"')\]}]+)""",
    )
    private val FILE_PATH = Regex("""(?:~|\B)/(?:[^\s/:"'\[\]]+/)+[^\s/:"'\[\]]*""")
    private val MEDIA_FILE = Regex(
        """(?i)[^\s/\\"'\[\]]+\.(?:mkv|mp4|m4v|mov|avi|wmv|flv|webm|mpe?g|ts|m3u8|mpd|mp3|m4a|m4b|aac|""" +
            """flac|wav|ogg|opus|srt|vtt|ass|ssa|sub|idx|pdf|epub|cbz|cbr|zip|rar|7z|tar|gz|iso|torrent|""" +
            """jpe?g|png|gif|heic|webp|txt|nfo|docx?)\b""",
    )

    private val SENSITIVE_KEYS = setOf(
        "body", "cookies", "email", "file", "fragment", "headers", "httpfragment", "httpquery",
        "ipaddress", "mail", "name", "path", "query", "querystring", "title", "username",
    )
    private val SENSITIVE_KEY_FRAGMENTS = listOf(
        "accesskey", "apikey", "assettitle", "auth", "cookie", "credential", "filename", "filepath",
        "mediatitle", "otp", "passwd", "password", "secret", "sessionid", "signature", "token",
    )
}
