package io.putdotio.android

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

internal fun monoValue(value: String, mono: SpanStyle): AnnotatedString =
    buildAnnotatedString { appendMonoValue(value, mono) }

internal fun AnnotatedString.Builder.appendMonoValue(value: String, mono: SpanStyle) {
    withStyle(mono) { append(value) }
}

/** [template] with its `%1$s`, `%2$s`… slots filled by [values] in [mono]. */
internal fun monoArguments(template: String, mono: SpanStyle, vararg values: String): AnnotatedString =
    buildAnnotatedString {
        var cursor = 0
        for (match in VALUE_SLOT.findAll(template)) {
            append(template.substring(cursor, match.range.first))
            appendMonoValue(values.getOrNull(match.groupValues[1].toInt() - 1).orEmpty(), mono)
            cursor = match.range.last + 1
        }
        append(template.substring(cursor))
    }

private val VALUE_SLOT = Regex("%(\\d+)\\\$s")
