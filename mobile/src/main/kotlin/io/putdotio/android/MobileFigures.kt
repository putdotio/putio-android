package io.putdotio.android

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

internal fun monoValue(value: String, mono: SpanStyle): AnnotatedString = AnnotatedString(value, mono)

internal fun AnnotatedString.Builder.appendMonoValue(value: String, mono: SpanStyle) {
    withStyle(mono) { append(value) }
}

/** [template] with its `%1$s`, `%2$s`… slots filled by [values] in [mono]. */
internal fun monoArguments(template: String, mono: SpanStyle, vararg values: String): AnnotatedString =
    styledTemplate(template, values.map { it to mono })

/** [template] with each `%n$s` slot filled from [slots] in its style. */
internal fun styledTemplate(template: String, slots: List<Pair<String, SpanStyle>>): AnnotatedString =
    buildAnnotatedString {
        var cursor = 0
        for (match in SLOT.findAll(template)) {
            append(template.substring(cursor, match.range.first))
            slots.getOrNull(match.groupValues[1].toInt() - 1)?.let { (value, style) -> withStyle(style) { append(value) } }
            cursor = match.range.last + 1
        }
        append(template.substring(cursor))
    }

private val SLOT = Regex("%(\\d+)\\\$s")
