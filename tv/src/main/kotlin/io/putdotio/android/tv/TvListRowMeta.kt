package io.putdotio.android.tv

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Fill
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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import kotlin.math.roundToInt

@Immutable
internal data class TvRowMeta(val parts: List<TvRowMetaPart>, val endsWithDot: Boolean = false)

@Immutable
internal data class TvRowMetaPart(val text: AnnotatedString, val mono: Boolean = false, val shrinks: Boolean = false) {
    constructor(text: String, mono: Boolean = false, shrinks: Boolean = false) :
        this(AnnotatedString(text), mono, shrinks)
}

@Composable
internal fun RowScope.TvListRowMeta(meta: TvRowMeta, color: Color) {
    if (meta.parts.isEmpty()) return
    val measurer = rememberTextMeasurer(cacheSize = 0)
    Spacer(Modifier.weight(1f, fill = false).then(TvRowMetaElement(meta, color, measurer)))
}

private data class TvRowMetaElement(
    val meta: TvRowMeta,
    val color: Color,
    val measurer: TextMeasurer,
) : ModifierNodeElement<TvRowMetaNode>() {
    override fun create() = TvRowMetaNode(meta, color, measurer)

    override fun update(node: TvRowMetaNode) = node.update(meta, color, measurer)

    override fun InspectorInfo.inspectableProperties() {
        name = "tvRowMeta"
        properties["meta"] = meta
        properties["color"] = color
    }
}

private class TvRowMetaRun(val layout: TextLayoutResult, var x: Int = 0, var y: Int = 0)

private class TvRowMetaNode(
    private var meta: TvRowMeta,
    private var color: Color,
    private var measurer: TextMeasurer,
) : Modifier.Node(),
    LayoutModifierNode,
    DrawModifierNode,
    SemanticsModifierNode {
    private var runs: List<TvRowMetaRun> = emptyList()
    private var laidOutWidth = NOT_LAID_OUT
    private var laidOutDensity = 0f
    private var laidOutFontScale = 0f
    private var laidOutDirection: LayoutDirection? = null
    private var width = 0
    private var height = 0

    fun update(meta: TvRowMeta, color: Color, measurer: TextMeasurer) {
        if (meta != this.meta || measurer != this.measurer) {
            this.meta = meta
            this.measurer = measurer
            laidOutWidth = NOT_LAID_OUT
            invalidateMeasurement()
            invalidateSemantics()
        }
        if (color != this.color) {
            this.color = color
            invalidateDraw()
        }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val stale = density != laidOutDensity || fontScale != laidOutFontScale || layoutDirection != laidOutDirection
        if (constraints.maxWidth != laidOutWidth || stale) {
            layOut(constraints.maxWidth)
            laidOutWidth = constraints.maxWidth
            laidOutDensity = density
            laidOutFontScale = fontScale
            laidOutDirection = layoutDirection
            invalidateDraw()
        }
        val shownWidth = constraints.constrainWidth(width)
        val shownHeight = constraints.constrainHeight(height)
        val placeable = measurable.measure(Constraints.fixed(shownWidth, shownHeight))
        return layout(shownWidth, shownHeight) { placeable.place(0, 0) }
    }

    private fun MeasureScope.layOut(maxWidth: Int) {
        val gap = TV_LIST_ROW_META_GAP.roundToPx()
        val dot = measure(DOT, TV_LIST_ROW_META)
        val parts = meta.parts.map { measure(it.text, it.style()) }.toMutableList()
        val separators = (parts.size - 1).coerceAtLeast(0) + if (meta.endsWithDot) 1 else 0
        val chrome = separators * (dot.size.width + 2 * gap) - if (meta.endsWithDot) gap else 0
        val natural = chrome + parts.sumOf { it.size.width }
        if (natural > maxWidth && parts.isNotEmpty()) {
            val shrinking = meta.parts.indexOfFirst { it.shrinks }.takeIf { it >= 0 } ?: parts.lastIndex
            val room = (maxWidth - (natural - parts[shrinking].size.width)).coerceAtLeast(0)
            parts[shrinking] = measure(meta.parts[shrinking].text, meta.parts[shrinking].style(), room)
        }
        val ordered = buildList {
            parts.forEachIndexed { index, part ->
                if (index > 0) add(TvRowMetaRun(dot))
                add(TvRowMetaRun(part))
            }
            if (meta.endsWithDot) add(TvRowMetaRun(dot))
        }
        height = ordered.maxOfOrNull { it.layout.size.height } ?: 0
        var x = 0
        ordered.forEach { run ->
            run.x = x
            run.y = ((height - run.layout.size.height) / 2f).roundToInt()
            x += run.layout.size.width + gap
        }
        width = (x - gap).coerceAtLeast(0)
        if (layoutDirection == LayoutDirection.Rtl) {
            ordered.forEach { it.x = width - it.x - it.layout.size.width }
        }
        runs = ordered
    }

    private fun MeasureScope.measure(text: AnnotatedString, style: TextStyle, maxWidth: Int? = null): TextLayoutResult =
        measurer.measure(
            text = text,
            style = style,
            overflow = if (maxWidth == null) TextOverflow.Clip else TextOverflow.Ellipsis,
            softWrap = maxWidth != null,
            maxLines = 1,
            constraints = if (maxWidth == null) Constraints() else Constraints(maxWidth = maxWidth),
            layoutDirection = layoutDirection,
            density = this,
        )

    override fun ContentDrawScope.draw() {
        val canvas = drawContext.canvas
        val clip = width > size.width
        if (clip) {
            canvas.save()
            canvas.clipRect(0f, 0f, size.width, size.height)
            if (layoutDirection == LayoutDirection.Rtl) canvas.translate(size.width - width, 0f)
        }
        runs.forEach { run ->
            canvas.save()
            canvas.translate(run.x.toFloat(), run.y.toFloat())
            run.layout.multiParagraph.paint(canvas = canvas, color = color, drawStyle = Fill)
            canvas.restore()
        }
        if (clip) canvas.restore()
    }

    override fun SemanticsPropertyReceiver.applySemantics() {
        this[SemanticsProperties.Text] = meta.parts.map { it.text }
    }
}

private fun TvRowMetaPart.style(): TextStyle = if (mono) TV_LIST_ROW_META_MONO else TV_LIST_ROW_META

private val DOT = AnnotatedString("·")
private const val NOT_LAID_OUT = -1
