package io.putdotio.android.design

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
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.node.invalidateSemantics
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalFontFamilyResolver
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import kotlin.math.roundToInt

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

public val MiddleCutShown: SemanticsPropertyKey<String> = SemanticsPropertyKey("MiddleCutShown")
private var SemanticsPropertyReceiver.middleCutShown by MiddleCutShown

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
) : Modifier.Node(),
    LayoutModifierNode,
    DrawModifierNode,
    SemanticsModifierNode,
    CompositionLocalConsumerModifierNode {
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
            reportRoom(constraints.maxWidth, style, maxLines, Density(density, fontScale), layoutDirection)
            val fit = fitMiddleCut(line, constraints.maxWidth, measurer, style, maxLines)
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
        layoutText(fitMiddleCut(line, width, measurer, style, maxLines).text, Constraints(maxWidth = width)).size.height

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

private fun CompositionLocalConsumerModifierNode.reportRoom(
    maxWidth: Int,
    style: TextStyle,
    maxLines: Int,
    density: Density,
    layoutDirection: LayoutDirection,
) {
    val cuts = currentValueOf(LocalPutioMiddleCuts) ?: return
    val resolver = currentValueOf(LocalFontFamilyResolver)
    cuts.seen(MiddleCutRoom(maxWidth, style, maxLines, density, layoutDirection, resolver))
}

private fun TextLayoutResult.fills(constraints: Constraints): Boolean =
    size.width >= constraints.minWidth && size.height in constraints.minHeight..constraints.maxHeight

private fun TextLayoutResult.shownText(): String {
    val shown = layoutInput.text.text
    val last = lineCount - 1
    return if (isLineEllipsized(last)) shown.substring(0, getLineEnd(last, visibleEnd = true)) + ELLIPSIS else shown
}

internal fun String.oneLine(): String = replace(LINE_BREAKS, " ")

private const val NO_CUT_WIDTH = -1
private val LINE_BREAKS = Regex("\\R")
