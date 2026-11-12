package io.putdotio.android

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import io.putdotio.android.files.FILE_SIZE_UNITS

internal fun monoFigures(text: String, mono: SpanStyle): AnnotatedString =
    buildAnnotatedString { appendMonoFigures(text, mono) }

internal fun AnnotatedString.Builder.appendMonoFigures(text: String, mono: SpanStyle) {
    var cursor = 0
    for (figure in FIGURE.findAll(text)) {
        append(text.substring(cursor, figure.range.first))
        withStyle(mono) { append(figure.value) }
        cursor = figure.range.last + 1
    }
    append(text.substring(cursor))
}

private val FIGURE = Regex("""\d+(?:[.,:]\d+)*(?:%| (?:${FILE_SIZE_UNITS.joinToString("|")})\b(?:/s)?)?""")
