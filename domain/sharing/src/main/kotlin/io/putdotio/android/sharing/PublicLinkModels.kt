package io.putdotio.android.sharing

import io.putdotio.android.PutioFailure
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.findPutioApiException
import io.putdotio.sdk.files.PutioFileType
import java.time.Instant

@JvmInline
public value class PublicLinkId(
    public val value: Long,
)

/**
 * Where put.io serves a public link: web's `/exclusive-access/<token>` page. The token is a bearer
 * secret for the shared item, so the address never prints.
 */
@JvmInline
public value class PublicLinkUrl internal constructor(
    public val value: String,
) {
    override fun toString(): String = "PublicLinkUrl(<redacted>)"

    internal companion object {
        /** The address of the link put.io issued [token] for. */
        fun of(token: String): PublicLinkUrl {
            require(token.isNotBlank()) { "A public link token cannot be blank" }
            return PublicLinkUrl(PUBLIC_LINK_BASE_URL + token.encodePathSegment())
        }
    }
}

/** An exclusive-access link to one of the viewer's own files or folders. */
public data class PublicLink(
    val id: PublicLinkId,
    val fileId: FilesItemId,
    val fileName: String,
    val fileType: PutioFileType,
    val url: PublicLinkUrl,
    /** When put.io stops serving it; null when put.io's timestamp does not parse. */
    val expiresAt: Instant?,
)

/** A refusal put.io names for a new link, which web words itself rather than showing put.io's text. */
public enum class PublicLinkRefusal {
    PLAN_NOT_ALLOWED,
    UNSUPPORTED_FILE_TYPE,
    LINK_LIMIT,
    FOLDER_TOO_BIG,
    FOLDER_TOO_MANY_FILES,
    DAILY_LIMIT,
    WEEKLY_LIMIT,
}

/**
 * The refusal behind a failed create, read from put.io's error type wherever the SDK wrapped it:
 * these come back as 403s, which the shared taxonomy files under access denied.
 */
internal val PutioFailure.publicLinkRefusal: PublicLinkRefusal?
    get() = when (cause.findPutioApiException()?.errorType) {
        "PUBLIC_SHARE_NOT_ALLOWED_PLAN" -> PublicLinkRefusal.PLAN_NOT_ALLOWED
        "PUBLIC_SHARE_UNSUPPORTED_FILE_TYPE" -> PublicLinkRefusal.UNSUPPORTED_FILE_TYPE
        "PUBLIC_SHARE_EXCEEDED_LIMIT",
        "PUBLIC_SHARE_SINGLE_FILE_LIMIT_EXCEEDED",
        "PUBLIC_SHARE_FOLDER_LINK_COUNT_LIMIT_EXCEEDED",
        -> PublicLinkRefusal.LINK_LIMIT
        "PUBLIC_SHARE_FOLDER_MAX_SIZE_LIMIT_EXCEEDED" -> PublicLinkRefusal.FOLDER_TOO_BIG
        "PUBLIC_SHARE_FOLDER_MAX_CHILDREN_LIMIT_EXCEEDED" -> PublicLinkRefusal.FOLDER_TOO_MANY_FILES
        "PUBLIC_SHARE_DAILY_TOTAL_LINK_COUNT_EXCEEDED" -> PublicLinkRefusal.DAILY_LIMIT
        "PUBLIC_SHARE_WEEKLY_TOTAL_LINK_COUNT_EXCEEDED" -> PublicLinkRefusal.WEEKLY_LIMIT
        else -> null
    }

// RFC 3986 unreserved characters pass; anything else is percent-encoded as UTF-8.
private fun String.encodePathSegment(): String =
    buildString {
        for (byte in this@encodePathSegment.encodeToByteArray()) {
            val char = (byte.toInt() and BYTE_MASK).toChar()
            if (char.isLetterOrDigit() && char.code < ASCII_LIMIT || char in UNRESERVED_MARKS) {
                append(char)
            } else {
                append('%').append(HEX[(byte.toInt() shr NIBBLE) and NIBBLE_MASK])
                    .append(HEX[byte.toInt() and NIBBLE_MASK])
            }
        }
    }

private const val PUBLIC_LINK_BASE_URL = "https://app.put.io/exclusive-access/"
private const val UNRESERVED_MARKS = "-._~"
private const val HEX = "0123456789ABCDEF"
private const val BYTE_MASK = 0xFF
private const val ASCII_LIMIT = 0x80
private const val NIBBLE = 4
private const val NIBBLE_MASK = 0x0F
