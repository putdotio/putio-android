package io.putdotio.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.node.invalidateSemantics
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.getTextLayoutResult
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.toSize
import java.text.BreakIterator
import kotlin.math.roundToInt

/**
 * A name on one line, or up to [maxLines], cut in the middle when it does not fit: it keeps its
 * start and the last [MIDDLE_CUT_TAIL] characters, so the extension and what comes just before it
 * stay readable ("Blue.Lights.S04E01…ORGANiC.mkv"). Accessibility reads the full name.
 */
@Composable
internal fun MobileMiddleCutText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = 1,
) {
    val measurer = rememberTextMeasurer()
    val textColor = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    Box(modifier) {
        Spacer(MiddleCutTextElement(text, style, textColor, maxLines, measurer))
    }
}

private data class MiddleCutTextElement(
    val text: String,
    val style: TextStyle,
    val color: Color,
    val maxLines: Int,
    val measurer: TextMeasurer,
) : ModifierNodeElement<MiddleCutTextNode>() {
    override fun create() = MiddleCutTextNode(text, style, color, maxLines, measurer)

    override fun update(node: MiddleCutTextNode) = node.update(text, style, color, maxLines, measurer)

    override fun InspectorInfo.inspectableProperties() {
        name = "middleCutText"
        properties["text"] = text
        properties["style"] = style
        properties["color"] = color
        properties["maxLines"] = maxLines
    }
}

private class MiddleCutTextNode(
    private var name: String,
    private var style: TextStyle,
    private var color: Color,
    private var maxLines: Int,
    private var measurer: TextMeasurer,
) : Modifier.Node(), LayoutModifierNode, DrawModifierNode, SemanticsModifierNode {
    private var line = name.oneLine()
    private var shown = line
    private var cutWidth = NO_CUT_WIDTH
    private var drawn = ""
    private var semanticsName: String? = null
    private var semanticsCut: String? = null
    private var laidOut: TextLayoutResult? = null

    fun update(name: String, style: TextStyle, color: Color, maxLines: Int, measurer: TextMeasurer) {
        this.name = name
        this.style = style
        this.color = color
        this.maxLines = maxLines
        this.measurer = measurer
        line = name.oneLine()
        cutWidth = NO_CUT_WIDTH
        invalidateMeasurement()
        invalidateDraw()
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val maxWidth = constraints.maxWidth
        if (maxWidth != cutWidth) {
            shown = if (maxLines == 1) {
                middleCut(line, maxWidth) { measurer.width(it, style) }
            } else {
                middleCutWhere(line, maxWidth) { measurer.fits(it, style, maxWidth, maxLines) }
            }
            cutWidth = maxWidth
        }
        val layout = measurer.measure(
            text = shown,
            style = style.merge(color = color),
            overflow = TextOverflow.Ellipsis,
            softWrap = maxLines > 1,
            maxLines = maxLines,
            constraints = constraints,
            layoutDirection = layoutDirection,
            density = this,
        )
        laidOut = layout
        drawn = layout.shownText()
        if (name != semanticsName || cut() != semanticsCut) invalidateSemantics()
        val size = layout.size
        val placeable = measurable.measure(
            Constraints.fitPrioritizingWidth(
                minWidth = size.width,
                maxWidth = size.width,
                minHeight = size.height,
                maxHeight = size.height,
            ),
        )
        return layout(
            size.width,
            size.height,
            mapOf(
                FirstBaseline to layout.firstBaseline.roundToInt(),
                LastBaseline to layout.lastBaseline.roundToInt(),
            ),
        ) { placeable.place(0, 0) }
    }

    override fun ContentDrawScope.draw() {
        val layout = laidOut ?: return
        val canvas = drawContext.canvas
        val clip = layout.hasVisualOverflow
        if (clip) {
            canvas.save()
            canvas.clipRect(Rect(Offset.Zero, layout.size.toSize()))
        }
        try {
            val shadow = style.shadow ?: Shadow.None
            val decoration = style.textDecoration ?: TextDecoration.None
            val drawStyle = style.drawStyle ?: Fill
            val brush = style.brush
            if (brush != null) {
                layout.multiParagraph.paint(
                    canvas = canvas,
                    brush = brush,
                    alpha = style.alpha,
                    shadow = shadow,
                    decoration = decoration,
                    drawStyle = drawStyle,
                )
            } else {
                layout.multiParagraph.paint(
                    canvas = canvas,
                    color = color.takeOrElse { Color.Black },
                    shadow = shadow,
                    decoration = decoration,
                    drawStyle = drawStyle,
                )
            }
        } finally {
            if (clip) canvas.restore()
        }
    }

    private fun cut(): String? = drawn.takeIf { shown != name }

    // A merged row reads its text in order, so the full name stands in for the cut one there;
    // a content description would hide the row's meta from TalkBack.
    override fun SemanticsPropertyReceiver.applySemantics() {
        semanticsName = name
        semanticsCut = cut()
        if (shown == name) {
            text = AnnotatedString(shown)
            getTextLayoutResult { results -> laidOut?.let(results::add) != null }
        } else {
            text = AnnotatedString(name)
            middleCutShown = drawn
        }
    }
}

/** What shows: a clipped end reads as cut there, so a lost extension cannot pass for a cut. */
private fun TextLayoutResult.shownText(): String {
    val shown = layoutInput.text.text
    val last = lineCount - 1
    return if (isLineEllipsized(last)) shown.substring(0, getLineEnd(last, visibleEnd = true)) + ELLIPSIS else shown
}

/** A hard line break would end the single line early, so the name shows on one line of spaces. */
private fun String.oneLine(): String = replace(LINE_BREAKS, " ")

private fun TextMeasurer.width(text: String, style: TextStyle): Int =
    measure(text, style, maxLines = 1, softWrap = false).size.width

/** Whether [text] wraps into [maxLines] lines of [maxWidth] without losing any of it. */
private fun TextMeasurer.fits(text: String, style: TextStyle, maxWidth: Int, maxLines: Int): Boolean =
    !measure(text, style, maxLines = maxLines, constraints = Constraints(maxWidth = maxWidth)).hasVisualOverflow

/**
 * The longest start of [text] that fits [maxWidth] with an ellipsis and the last [MIDDLE_CUT_TAIL]
 * characters; [text] itself when it fits. A width too narrow for that tail keeps fewer of its last
 * characters; one too narrow for an ellipsis and a single character leaves the end cut to the
 * text's own ellipsis.
 */
internal fun middleCut(text: String, maxWidth: Int, widthOf: (String) -> Int): String =
    middleCutWhere(text, maxWidth) { widthOf(it) <= maxWidth }

/** [middleCut] for any room: the longest cut that [fits], or [text] itself when it fits. */
private fun middleCutWhere(text: String, maxWidth: Int, fits: (String) -> Boolean): String {
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

/** The text a cut name laid out, while accessibility reads the full name; for tests. */
internal val MiddleCutShown = SemanticsPropertyKey<String>("MiddleCutShown")
private var SemanticsPropertyReceiver.middleCutShown by MiddleCutShown

/** Characters kept after the cut: the extension and a few before it. */
internal const val MIDDLE_CUT_TAIL = 11
private const val ELLIPSIS = "…"
private const val NO_CUT_WIDTH = -1
private val LINE_BREAKS = Regex("\\R")
