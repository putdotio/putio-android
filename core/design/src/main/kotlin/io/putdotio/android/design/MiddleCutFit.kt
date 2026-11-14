package io.putdotio.android.design

import android.util.LruCache
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import java.util.Locale

internal class MiddleCutFit(val text: String, val layout: TextLayoutResult? = null)

internal fun IntrinsicMeasureScope.fitMiddleCut(
    line: String,
    maxWidth: Int,
    measurer: TextMeasurer,
    style: TextStyle,
    maxLines: Int,
): MiddleCutFit {
    if (maxLines != 1) {
        return MiddleCutFit(middleCutWhere(line, maxWidth) { measurer.fitsLines(it, style, maxWidth, maxLines) })
    }
    val key = OneLineCut(line, maxWidth, style, Locale.getDefault(), density, fontScale, layoutDirection)
    return OneLineCuts[key]?.let(::MiddleCutFit) ?: fitOneLine(line, maxWidth, measurer, style, key)
}

private fun IntrinsicMeasureScope.fitOneLine(
    line: String,
    maxWidth: Int,
    measurer: TextMeasurer,
    style: TextStyle,
    key: OneLineCut,
): MiddleCutFit {
    val whole = oneLineLayout(line, measurer, style)
    if (maxWidth !in 1 until Int.MAX_VALUE || whole.size.width <= maxWidth) {
        return MiddleCutFit(line, whole.takeIf { it.size.width <= maxWidth })
    }
    var measured: TextLayoutResult? = null
    val cut = middleCut(line, maxWidth, whole.middleCutLayout(measurer, style)) { candidate ->
        oneLineLayout(candidate, measurer, style).also { measured = it }.size.width
    }
    OneLineCuts.put(key, cut)
    return MiddleCutFit(cut, measured?.takeIf { it.layoutInput.text.text == cut && it.size.width <= maxWidth })
}

private fun IntrinsicMeasureScope.oneLineLayout(text: String, measurer: TextMeasurer, style: TextStyle) =
    measurer.measure(
        text = text,
        style = style,
        maxLines = 1,
        softWrap = false,
        layoutDirection = layoutDirection,
        density = this,
    )

private fun TextLayoutResult.middleCutLayout(measurer: TextMeasurer, style: TextStyle): MiddleCutLayout {
    val ellipsisWidth = { measurer.measure(ELLIPSIS, style, maxLines = 1, softWrap = false).size.width.toFloat() }
    val leftToRight = getParagraphDirection(0) == ResolvedTextDirection.Ltr
    return MiddleCutLayout(size.width, ellipsisWidth, leftToRight) { getHorizontalPosition(it, true) }
}

/** Whether [text] wraps into [maxLines] lines of [maxWidth] without losing any of it. */
private fun TextMeasurer.fitsLines(text: String, style: TextStyle, maxWidth: Int, maxLines: Int): Boolean =
    !measure(text, style, maxLines = maxLines, constraints = Constraints(maxWidth = maxWidth)).hasVisualOverflow

private data class OneLineCut(
    val text: String,
    val maxWidth: Int,
    val style: TextStyle,
    val locale: Locale,
    val density: Float,
    val fontScale: Float,
    val layoutDirection: LayoutDirection,
)

private val OneLineCuts = LruCache<OneLineCut, String>(ONE_LINE_CUTS)

private const val ONE_LINE_CUTS = 512
