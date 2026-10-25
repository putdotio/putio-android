package io.putdotio.android

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import java.text.BreakIterator

/**
 * A name on one line, cut in the middle when it does not fit: it keeps its start and the last
 * [MIDDLE_CUT_TAIL] characters, so the extension and what comes just before it stay readable
 * ("Blue.Lights.S04E01…ORGANiC.mkv"). Accessibility reads the full name.
 */
@Composable
internal fun MobileMiddleCutText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
) {
    val measurer = rememberTextMeasurer()
    // A hard line break would end the single line early, so the name shows on one line of spaces.
    val line = remember(text) { text.replace(LINE_BREAKS, " ") }
    // What the text actually laid out, read back for tests through [MiddleCutShown].
    var drawn by remember { mutableStateOf("") }
    BoxWithConstraints(modifier) {
        val maxWidth = constraints.maxWidth
        val shown = remember(line, style, maxWidth) { middleCut(line, maxWidth) { measurer.width(it, style) } }
        Text(
            text = shown,
            style = style,
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { drawn = it.layoutInput.text.text },
            // A merged row reads its text in order, so the full name stands in for the cut one there;
            // a content description would hide the row's meta from TalkBack.
            modifier = if (shown == text) {
                Modifier
            } else {
                Modifier.clearAndSetSemantics {
                    this.text = AnnotatedString(text)
                    middleCutShown = drawn
                }
            },
        )
    }
}

private fun TextMeasurer.width(text: String, style: TextStyle): Int =
    measure(text, style, maxLines = 1, softWrap = false).size.width

/**
 * The longest start of [text] that fits [maxWidth] with an ellipsis and the last [MIDDLE_CUT_TAIL]
 * characters; [text] itself when it fits. A width too narrow for that tail keeps fewer of its last
 * characters; one too narrow for an ellipsis and a single character leaves the end cut to the
 * text's own ellipsis.
 */
internal fun middleCut(text: String, maxWidth: Int, widthOf: (String) -> Int): String {
    if (maxWidth !in 1 until Int.MAX_VALUE || widthOf(text) <= maxWidth) return text
    // Cuts fall between characters as the user sees them, never inside an emoji or a surrogate pair.
    val boundaries = BreakIterator.getCharacterInstance().apply { setText(text) }
        .let { breaks -> generateSequence(breaks.first()) { breaks.next().takeIf { it != BreakIterator.DONE } } }
        .toList()
    // The tail keeps the last [MIDDLE_CUT_TAIL] characters, fewer when even those don't fit.
    val tails = boundaries.filter { it in 1 until text.length && text.length - it <= MIDDLE_CUT_TAIL }
    val tailStart = tails.firstOrNull { widthOf(ELLIPSIS + text.substring(it)) <= maxWidth }
    return tailStart?.let { longestFit(text, boundaries.filter { start -> start < it }, it, maxWidth, widthOf) }
        ?: text
}

/** The cut with the longest start that still fits, by binary search over the boundaries before the tail. */
private fun longestFit(
    text: String,
    starts: List<Int>,
    tailStart: Int,
    maxWidth: Int,
    widthOf: (String) -> Int,
): String {
    fun cut(startEnd: Int) = text.substring(0, startEnd).trimEnd() + ELLIPSIS + text.substring(tailStart)
    var low = 0
    var high = starts.lastIndex
    while (low < high) {
        val middle = (low + high + 1) / 2
        if (widthOf(cut(starts[middle])) <= maxWidth) low = middle else high = middle - 1
    }
    return cut(starts[low])
}

/** The text a cut name laid out, while accessibility reads the full name; for tests. */
internal val MiddleCutShown = SemanticsPropertyKey<String>("MiddleCutShown")
private var SemanticsPropertyReceiver.middleCutShown by MiddleCutShown

/** Characters kept after the cut: the extension and a few before it. */
internal const val MIDDLE_CUT_TAIL = 11
private const val ELLIPSIS = "…"
private val LINE_BREAKS = Regex("\\R")
