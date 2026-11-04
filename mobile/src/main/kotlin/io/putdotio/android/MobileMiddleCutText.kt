package io.putdotio.android

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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.toSize
import io.putdotio.android.design.MIDDLE_CUT_TAIL
import io.putdotio.android.design.fitsLines
import io.putdotio.android.design.middleCut
import io.putdotio.android.design.middleCutWhere
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
    Spacer(modifier.then(MiddleCutTextElement(text, style, textColor, maxLines, measurer)))
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
        val relayout = name != this.name || style != this.style || maxLines != this.maxLines ||
            measurer != this.measurer
        this.name = name
        this.style = style
        this.color = color
        this.maxLines = maxLines
        this.measurer = measurer
        if (relayout) {
            line = name.oneLine()
            cutWidth = NO_CUT_WIDTH
            invalidateMeasurement()
        }
        invalidateDraw()
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        if (constraints.maxWidth != cutWidth) {
            shown = measurer.cutToFit(line, style, constraints.maxWidth, maxLines)
            cutWidth = constraints.maxWidth
        }
        val layout = layoutText(shown, constraints)
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
        layoutText(measurer.cutToFit(line, style, width, maxLines), Constraints(maxWidth = width)).size.height

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
        if (shown == name) {
            text = AnnotatedString(shown)
            getTextLayoutResult { results -> laidOut?.let(results::add) != null }
        } else {
            text = AnnotatedString(name)
            middleCutShown = drawn
        }
    }
}

private fun TextLayoutResult.shownText(): String {
    val shown = layoutInput.text.text
    val last = lineCount - 1
    return if (isLineEllipsized(last)) shown.substring(0, getLineEnd(last, visibleEnd = true)) + ELLIPSIS else shown
}

private fun String.oneLine(): String = replace(LINE_BREAKS, " ")

private fun TextMeasurer.cutToFit(line: String, style: TextStyle, maxWidth: Int, maxLines: Int): String =
    if (maxLines == 1) {
        middleCut(line, maxWidth) { width(it, style) }
    } else {
        middleCutWhere(line, maxWidth) { fitsLines(it, style, maxWidth, maxLines) }
    }

private fun TextMeasurer.width(text: String, style: TextStyle): Int =
    measure(text, style, maxLines = 1, softWrap = false).size.width

/** The text a cut name laid out, while accessibility reads the full name; for tests. */
internal val MiddleCutShown = SemanticsPropertyKey<String>("MiddleCutShown")
private var SemanticsPropertyReceiver.middleCutShown by MiddleCutShown

private const val ELLIPSIS = "…"
private const val NO_CUT_WIDTH = -1
private val LINE_BREAKS = Regex("\\R")
