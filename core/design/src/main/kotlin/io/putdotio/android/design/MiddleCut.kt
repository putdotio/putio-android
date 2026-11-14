package io.putdotio.android.design

import android.util.LruCache
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
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
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A name on one line, or up to [maxLines], cut in the middle when it does not fit: it keeps its
 * start and the last [MIDDLE_CUT_TAIL] characters, so the extension and what comes just before it
 * stay readable ("Blue.Lights.S04E01…ORGANiC.mkv"). Accessibility reads the full name. It draws in
 * [color], else the style's colour, else [contentColor].
 */
@Composable
public fun PutioMiddleCutText(
    text: String,
    style: TextStyle,
    contentColor: Color,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    maxLines: Int = 1,
) {
    val measurer = rememberTextMeasurer()
    val drawColor = color.takeOrElse { style.color.takeOrElse { contentColor } }
    Spacer(modifier.then(MiddleCutTextElement(text, style, drawColor, maxLines, measurer)))
}

/** The text a cut name laid out, while accessibility reads the full name; for tests. */
public val MiddleCutShown: SemanticsPropertyKey<String> = SemanticsPropertyKey("MiddleCutShown")
private var SemanticsPropertyReceiver.middleCutShown by MiddleCutShown

/** Characters kept after the cut: the extension and a few before it. */
public const val MIDDLE_CUT_TAIL: Int = 11

private data class MiddleCutTextElement(
    val text: String,
    val style: TextStyle,
    val color: Color,
    val maxLines: Int,
    val measurer: TextMeasurer,
) : ModifierNodeElement<MiddleCutTextNode>() {
    override fun create() = MiddleCutTextNode(this)

    override fun update(node: MiddleCutTextNode) = node.update(this)

    override fun InspectorInfo.inspectableProperties() {
        name = "middleCutText"
        properties["text"] = text
        properties["style"] = style
        properties["color"] = color
        properties["maxLines"] = maxLines
    }
}

private class MiddleCutTextNode(
    private var element: MiddleCutTextElement,
) : Modifier.Node(), LayoutModifierNode, DrawModifierNode, SemanticsModifierNode {
    private val name get() = element.text
    private val style get() = element.style
    private val maxLines get() = element.maxLines
    private val measurer get() = element.measurer
    private var line = name.oneLine()
    private var shown = line
    private var cutWidth = NO_CUT_WIDTH
    private var drawn = ""
    private var semanticsName: String? = null
    private var semanticsCut: String? = null
    private var laidOut: TextLayoutResult? = null
    private var fitted: TextLayoutResult? = null

    fun update(element: MiddleCutTextElement) {
        val relayout = element.copy(color = this.element.color) != this.element
        this.element = element
        if (relayout) {
            line = name.oneLine()
            cutWidth = NO_CUT_WIDTH
            invalidateMeasurement()
        }
        invalidateDraw()
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val fittedFor = fitted?.layoutInput
        val scopeChanged = fittedFor != null &&
            (fittedFor.density.density != density || fittedFor.density.fontScale != fontScale ||
                fittedFor.layoutDirection != layoutDirection)
        if (constraints.maxWidth != cutWidth || scopeChanged) {
            val fit = fit(constraints.maxWidth)
            shown = fit.text
            fitted = fit.layout
            cutWidth = constraints.maxWidth
        }
        val layout = fitted?.takeIf { it.fills(constraints) } ?: layoutText(shown, constraints)
        if (layout.layoutInput != laidOut?.layoutInput) invalidateDraw()
        laidOut = layout
        drawn = layout.shownText()
        val cut = cut()
        if (name != semanticsName || cut != semanticsCut) {
            semanticsName = name
            semanticsCut = cut
            invalidateSemantics()
        }
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

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int): Int =
        maxIntrinsicWidth(measurable, height)

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int): Int =
        layoutText(line, Constraints()).size.width

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int): Int =
        maxIntrinsicHeight(measurable, width)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int): Int =
        layoutText(fit(width).text, Constraints(maxWidth = width)).size.height

    private fun IntrinsicMeasureScope.fit(maxWidth: Int): Fit {
        if (maxLines != 1) {
            return Fit(middleCutWhere(line, maxWidth) { measurer.fitsLines(it, style, maxWidth, maxLines) })
        }
        val key = OneLineCut(line, maxWidth, style, Locale.getDefault(), density, fontScale, layoutDirection)
        OneLineCuts[key]?.let { return Fit(it) }
        val whole = oneLineLayout(line)
        if (maxWidth !in 1 until Int.MAX_VALUE || whole.size.width <= maxWidth) {
            return Fit(line, whole.takeIf { it.size.width <= maxWidth })
        }
        var measured: TextLayoutResult? = null
        val cut = middleCut(line, maxWidth, whole.middleCutLayout()) { candidate ->
            oneLineLayout(candidate).also { measured = it }.size.width
        }
        OneLineCuts.put(key, cut)
        return Fit(cut, measured?.takeIf { it.layoutInput.text.text == cut && it.size.width <= maxWidth })
    }

    private fun IntrinsicMeasureScope.oneLineLayout(text: String): TextLayoutResult =
        measurer.measure(
            text = text,
            style = style,
            maxLines = 1,
            softWrap = false,
            layoutDirection = layoutDirection,
            density = this,
        )

    private fun TextLayoutResult.middleCutLayout(): MiddleCutLayout {
        val ellipsisWidth = { measurer.measure(ELLIPSIS, style, maxLines = 1, softWrap = false).size.width.toFloat() }
        val leftToRight = getParagraphDirection(0) == ResolvedTextDirection.Ltr
        return MiddleCutLayout(size.width, ellipsisWidth, leftToRight) { getHorizontalPosition(it, true) }
    }

    private fun IntrinsicMeasureScope.layoutText(text: String, constraints: Constraints): TextLayoutResult =
        measurer.measure(
            text = text,
            style = style,
            overflow = TextOverflow.Ellipsis,
            softWrap = maxLines > 1,
            maxLines = maxLines,
            constraints = constraints,
            layoutDirection = layoutDirection,
            density = this,
        )

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
                    color = element.color.takeOrElse { Color.Black },
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

    override fun SemanticsPropertyReceiver.applySemantics() {
        if (shown == name) {
            text = AnnotatedString(shown)
            getTextLayoutResult { results -> laidOut?.let(results::add) != null }
        } else {
            text = AnnotatedString(name)
            middleCutShown = drawn
        }
    }
}

private class Fit(val text: String, val layout: TextLayoutResult? = null)

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

private fun TextLayoutResult.fills(constraints: Constraints): Boolean =
    size.width >= constraints.minWidth && size.height in constraints.minHeight..constraints.maxHeight

/** Whether [text] wraps into [maxLines] lines of [maxWidth] without losing any of it. */
private fun TextMeasurer.fitsLines(text: String, style: TextStyle, maxWidth: Int, maxLines: Int): Boolean =
    !measure(text, style, maxLines = maxLines, constraints = Constraints(maxWidth = maxWidth)).hasVisualOverflow

private fun TextLayoutResult.shownText(): String {
    val shown = layoutInput.text.text
    val last = lineCount - 1
    return if (isLineEllipsized(last)) shown.substring(0, getLineEnd(last, visibleEnd = true)) + ELLIPSIS else shown
}

private fun String.oneLine(): String = replace(LINE_BREAKS, " ")

private const val NO_CUT_WIDTH = -1
private const val ONE_LINE_CUTS = 512
private val LINE_BREAKS = Regex("\\R")
