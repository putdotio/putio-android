package io.putdotio.android

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

internal fun monoFigures(text: String, mono: SpanStyle): AnnotatedString =
    buildAnnotatedString { appendMonoFigures(text, mono) }

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
