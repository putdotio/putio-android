package io.putdotio.android.design

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import java.text.BreakIterator

/**
 * The longest start of [text] that fits [maxWidth] with an ellipsis and the last [MIDDLE_CUT_TAIL]
 * characters; [text] itself when it fits. A width too narrow for that tail keeps fewer of its last
 * characters; one too narrow for an ellipsis and a single character leaves the end cut to the
 * text's own ellipsis.
 */
public fun middleCut(text: String, maxWidth: Int, widthOf: (String) -> Int): String =
    middleCutWhere(text, maxWidth) { widthOf(it) <= maxWidth }

/** [middleCut] for any room: the longest cut that [fits], or [text] itself when it fits. */
public fun middleCutWhere(text: String, maxWidth: Int, fits: (String) -> Boolean): String {
    if (maxWidth !in 1 until Int.MAX_VALUE || fits(text)) return text
    // Cuts fall between characters as the user sees them, never inside an emoji or a surrogate pair.
    val boundaries = BreakIterator.getCharacterInstance().apply { setText(text) }
        .let { breaks -> generateSequence(breaks.first()) { breaks.next().takeIf { it != BreakIterator.DONE } } }
        .toList()
    // The tail keeps the last [MIDDLE_CUT_TAIL] characters, fewer when even those don't fit.
    val tails = boundaries.filter { it in 1 until text.length && text.length - it <= MIDDLE_CUT_TAIL }
    val tailStart = tails.firstOrNull { fits(ELLIPSIS + text.substring(it)) }
    return tailStart?.let { longestFit(text, boundaries.filter { start -> start < it }, it, fits) }
        ?: text
}

/** Whether [text] wraps into [maxLines] lines of [maxWidth] without losing any of it. */
public fun TextMeasurer.fitsLines(text: String, style: TextStyle, maxWidth: Int, maxLines: Int): Boolean =
    !measure(text, style, maxLines = maxLines, constraints = Constraints(maxWidth = maxWidth)).hasVisualOverflow

/** The cut with the longest start that still fits, by binary search over the boundaries before the tail. */
private fun longestFit(
    text: String,
    starts: List<Int>,
    tailStart: Int,
    fits: (String) -> Boolean,
): String {
    fun cut(startEnd: Int) = text.substring(0, startEnd).trimEnd() + ELLIPSIS + text.substring(tailStart)
    var low = 0
    var high = starts.lastIndex
    while (low < high) {
        val middle = (low + high + 1) / 2
        if (fits(cut(starts[middle]))) low = middle else high = middle - 1
    }
    return cut(starts[low])
}

/** Characters kept after the cut: the extension and a few before it. */
public const val MIDDLE_CUT_TAIL: Int = 11
private const val ELLIPSIS = "…"
