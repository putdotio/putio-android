package io.putdotio.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow

/**
 * A name cut in the middle to fit one line, keeping its start and the last [MIDDLE_CUT_TAIL]
 * characters before and including the extension ("Blue.Lights.S04E01…ORGANiC.mkv"). Screen readers
 * and tests read the full name.
 */
@Composable
internal fun MobileMiddleCutText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth
        val shown = remember(text, width, style, measurer) {
            middleCut(text) { candidate ->
                measurer.measure(candidate, style, maxLines = 1, softWrap = false).size.width <= width
            }
        }
        // Inside the subcomposition, so the cut line's own text never reaches the semantics tree.
        Box(Modifier.clearAndSetSemantics { this.text = AnnotatedString(text) }) {
            Text(
                text = shown,
                style = style,
                color = color,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The longest middle cut of [name] that [fits], or the name itself when it fits whole. When not
 * even the kept end fits, the end is returned and the caller's end ellipsis takes over.
 */
internal fun middleCut(name: String, fits: (String) -> Boolean): String {
    if (fits(name)) return name
    // A leading dot or a long tail after the last dot is part of the name, not an extension.
    val extension = name.substringAfterLast('.', "")
        .takeIf { name.lastIndexOf('.') > 0 && it.isNotEmpty() && it.length <= MAX_EXTENSION }
    val stem = if (extension == null) name else name.dropLast(extension.length + 1)
    // Cuts fall between code points, so an emoji is kept or dropped whole.
    var tailStart = (stem.length - MIDDLE_CUT_TAIL).coerceAtLeast(0)
    if (tailStart > 0 && stem[tailStart].isLowSurrogate()) tailStart--
    val tail = stem.substring(tailStart) + extension?.let { ".$it" }.orEmpty()
    val head = name.dropLast(tail.length)
    fun start(length: Int): String =
        head.take(if (length > 0 && head[length - 1].isHighSurrogate()) length - 1 else length).trimEnd()
    // Binary search the longest start that still fits beside the ellipsis and the kept end.
    var low = 0
    var high = head.length
    while (low < high) {
        val mid = (low + high + 1) / 2
        if (fits(start(mid) + ELLIPSIS + tail)) low = mid else high = mid - 1
    }
    return start(low) + ELLIPSIS + tail
}

internal const val MIDDLE_CUT_TAIL = 7
private const val MAX_EXTENSION = 8
private const val ELLIPSIS = "…"
