package io.putdotio.android

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import io.putdotio.android.files.formatFileSize

/** A size as every mobile screen writes it: "276 MB", decimal units, a space before the unit. */
internal fun mobileFileSize(bytes: Long): String = formatFileSize(bytes, spaced = true)

/** [text] with its digits in [mono]; see [appendMonoFigures]. */
internal fun monoFigures(text: String, mono: SpanStyle): AnnotatedString =
    buildAnnotatedString { appendMonoFigures(text, mono) }

/**
 * Appends [text] with only its digits in [mono]. The point, the space and the unit stay in the
 * text's own face: mono sets each in a full cell, which opens gaps such as "9 . 1  GB".
 */
internal fun AnnotatedString.Builder.appendMonoFigures(text: String, mono: SpanStyle) {
    var start = 0
    while (start < text.length) {
        val digits = text[start].isDigit()
        var end = start
        while (end < text.length && text[end].isDigit() == digits) end++
        val run = text.substring(start, end)
        if (digits) withStyle(mono) { append(run) } else append(run)
        start = end
    }
}
