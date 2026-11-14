package io.putdotio.android.design

import java.text.Bidi
import java.text.BreakIterator
import kotlin.math.abs

/**
 * The longest start of [text] that fits [maxWidth] with an ellipsis and the last [MIDDLE_CUT_TAIL]
 * characters; [text] itself when it fits. A width too narrow for that tail keeps fewer of its last
 * characters; one too narrow for an ellipsis and a single character leaves the end cut to the
 * text's own ellipsis.
 */
internal fun middleCut(text: String, maxWidth: Int, widthOf: (String) -> Int): String =
    middleCutWhere(text, maxWidth) { widthOf(it) <= maxWidth }

internal class MiddleCutLayout(
    val width: Int,
    val ellipsisWidth: () -> Float,
    val leftToRight: Boolean = true,
    val offsetX: (Int) -> Float,
)

internal fun middleCut(text: String, maxWidth: Int, layout: MiddleCutLayout, widthOf: (String) -> Int): String {
    if (maxWidth !in 1 until Int.MAX_VALUE || layout.width <= maxWidth) return text
    val fits = { cut: String -> widthOf(cut) <= maxWidth }
    val unidirectional = layout.leftToRight && !Bidi.requiresBidi(text.toCharArray(), 0, text.length)
    val priced = if (unidirectional) pricedCut(text, maxWidth, layout) else null
    return priced?.tries(text)?.firstOrNull(fits) ?: middleCutWhere(text, maxWidth, fits)
}

/** [middleCut] for any room: the longest cut that [fits], or [text] itself when it fits. */
internal fun middleCutWhere(text: String, maxWidth: Int, fits: (String) -> Boolean): String {
    if (maxWidth !in 1 until Int.MAX_VALUE || fits(text)) return text
    val boundaries = text.characterBoundaries()
    val tailStart = text.tailStarts(boundaries).firstOrNull { fits(ELLIPSIS + text.substring(it)) }
    return tailStart?.let { longestFit(text, boundaries.filter { start -> start < it }, it, fits) }
        ?: text
}

private class PricedCut(val starts: List<Int>, val start: Int, val tailStart: Int) {
    fun tries(text: String): Sequence<String> =
        (start downTo (start - PRICED_TRIES + 1).coerceAtLeast(0)).asSequence()
            .map { cutAt(text, starts[it], tailStart) }
}

private fun pricedCut(text: String, maxWidth: Int, layout: MiddleCutLayout): PricedCut? {
    val left = layout.offsetX(0)
    val right = layout.offsetX(text.length)
    val ellipsisWidth = layout.ellipsisWidth()
    val boundaries = text.characterBoundaries()
    val tailStart = text.tailStarts(boundaries)
        .firstOrNull { ellipsisWidth + abs(right - layout.offsetX(it)) <= maxWidth }
        ?: return null
    val room = maxWidth - ellipsisWidth - abs(right - layout.offsetX(tailStart))
    val starts = boundaries.filter { it < tailStart }
    var low = 0
    var high = starts.lastIndex
    while (low < high) {
        val middle = (low + high + 1) / 2
        if (abs(layout.offsetX(text.trimmedEnd(starts[middle])) - left) <= room) low = middle else high = middle - 1
    }
    return PricedCut(starts, low, tailStart)
}

private fun String.characterBoundaries(): List<Int> =
    BreakIterator.getCharacterInstance().apply { setText(this@characterBoundaries) }
        .let { breaks -> generateSequence(breaks.first()) { breaks.next().takeIf { it != BreakIterator.DONE } } }
        .toList()

private fun String.tailStarts(boundaries: List<Int>): List<Int> =
    boundaries.filter { it in 1 until length && length - it <= MIDDLE_CUT_TAIL }

private fun String.trimmedEnd(end: Int): Int = substring(0, end).trimEnd().length

private fun cutAt(text: String, startEnd: Int, tailStart: Int): String =
    text.substring(0, startEnd).trimEnd() + ELLIPSIS + text.substring(tailStart)

/** The cut with the longest start that still fits, by binary search over the boundaries before the tail. */
private fun longestFit(
    text: String,
    starts: List<Int>,
    tailStart: Int,
    fits: (String) -> Boolean,
): String {
    fun cut(startEnd: Int) = cutAt(text, startEnd, tailStart)
    var low = 0
    var high = starts.lastIndex
    while (low < high) {
        val middle = (low + high + 1) / 2
        if (fits(cut(starts[middle]))) low = middle else high = middle - 1
    }
    return cut(starts[low])
}

internal const val ELLIPSIS = "…"
private const val PRICED_TRIES = 3
