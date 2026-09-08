package io.putdotio.android

import android.content.Intent
import io.putdotio.android.transfers.TransferSubmission

internal enum class MobileShareValidation {
    InvalidLink,
    MultipleLinks,
    TooLong,
}

internal class MobileSharedTransfer(
    val input: String,
    val validation: MobileShareValidation? = null,
) {
    override fun toString(): String = "MobileSharedTransfer(<redacted>, validation=$validation)"
}

/** Removes the payload even when malformed; only the in-memory draft may retain shared text. */
internal fun Intent.consumeMobileSharedTransfer(): MobileSharedTransfer? {
    if (action != Intent.ACTION_SEND || type != "text/plain") return null
    return try {
        val value = getCharSequenceExtra(Intent.EXTRA_TEXT)
        if (value != null && !value.fitsMobileTransferInputLimit()) {
            MobileSharedTransfer("", MobileShareValidation.TooLong)
        } else {
            parseMobileSharedTransfer(value?.toString().orEmpty())
        }
    } catch (_: android.os.BadParcelableException) {
        MobileSharedTransfer("", MobileShareValidation.InvalidLink)
    } catch (_: ClassCastException) {
        MobileSharedTransfer("", MobileShareValidation.InvalidLink)
    } finally {
        replaceExtras(android.os.Bundle())
        clipData = null
        setDataAndType(null, type)
        selector = null
    }
}

internal fun parseMobileSharedTransfer(text: String): MobileSharedTransfer {
    if (!text.fitsMobileTransferInputLimit()) return MobileSharedTransfer("", MobileShareValidation.TooLong)
    val trimmed = text.trim()
    if (TransferSubmission.parse(trimmed) != null) return MobileSharedTransfer(trimmed)
    val links = SharedLink.findAll(trimmed)
        .map { match -> match.value.removeUnmatchedClosingDelimiters() }
        .distinct()
        .take(2)
        .toList()
    return when (links.size) {
        1 -> if (links.single().last() !in AmbiguousProseEndings && TransferSubmission.parse(links.single()) != null) {
            MobileSharedTransfer(links.single())
        } else {
            MobileSharedTransfer(text, MobileShareValidation.InvalidLink)
        }
        0 -> MobileSharedTransfer(text, MobileShareValidation.InvalidLink)
        else -> MobileSharedTransfer(text, MobileShareValidation.MultipleLinks)
    }
}

private fun String.removeUnmatchedClosingDelimiters(): String {
    val unmatched = listOf('(' to ')', '[' to ']', '{' to '}').associate { (opening, closing) ->
        closing to (count { it == closing } - count { it == opening })
    }.toMutableMap()
    var end = length
    while (end > 0) {
        val closing = this[end - 1]
        val remaining = unmatched[closing] ?: break
        if (remaining <= 0) break
        unmatched[closing] = remaining - 1
        end--
    }
    return substring(0, end)
}

internal fun CharSequence.fitsMobileTransferInputLimit(): Boolean =
    length <= MOBILE_TRANSFER_INPUT_LIMIT && toString().toByteArray(Charsets.UTF_8).size <= MOBILE_TRANSFER_INPUT_LIMIT

internal const val MOBILE_TRANSFER_INPUT_LIMIT = 16 * 1024
private const val AmbiguousProseEndings = ".,;:!?'"
private val SharedLink = Regex("(?:https?://|magnet:\\?)[^\\s<>\\\"“”]+", RegexOption.IGNORE_CASE)
