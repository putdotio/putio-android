package io.putdotio.android.tv

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioMiddleCutText
import io.putdotio.android.design.PutioRadius
import io.putdotio.android.design.PutioTypeScale
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TvListDetail(
    list: @Composable ColumnScope.() -> Unit,
    detail: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    detailFocus: FocusRequester = FocusRequester.Default,
    listFocus: FocusRequester = FocusRequester.Default,
    onDetailFocusChanged: (Boolean) -> Unit = {},
    header: @Composable ColumnScope.() -> Unit = {},
) {
    Row(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .width(TV_LIST_WIDTH)
                .fillMaxHeight(),
        ) {
            Column(modifier = Modifier.width(TV_LIST_WIDTH - ROW_BLEED), content = header)
            Column(
                modifier = Modifier
                    .offset(x = -ROW_BLEED)
                    .fillMaxWidth()
                    .weight(1f),
                content = list,
            )
        }
        Spacer(Modifier.width(DETAIL_GAP))
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(PutioRadius.large))
                .padding(DETAIL_PANEL_PADDING)
                .onFocusChanged { onDetailFocusChanged(it.hasFocus) }
                .focusProperties {
                    enter = { detailFocus }
                    exit = { if (it == FocusDirection.Left) listFocus else FocusRequester.Default }
                    down = FocusRequester.Cancel
                }
                .focusGroup(),
            content = detail,
        )
    }
}

@Composable
internal fun TvSectionLine(
    modifier: Modifier = Modifier,
    pill: (@Composable () -> Unit)? = null,
    label: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = ROW_BLEED, bottom = SECTION_LINE_GAP)
            .height(SECTION_PILL_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(SECTION_LABEL_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) { label() }
        Spacer(Modifier.weight(1f))
        pill?.invoke()
    }
}

@Composable
internal fun TvSectionPill(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showFocus: Boolean = true,
    interactions: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val rest = PutioDesignTokens.componentBgActive
    val restContent = MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = onClick,
        modifier = modifier.height(SECTION_PILL_HEIGHT),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = rest,
            contentColor = restContent,
            focusedContainerColor = if (showFocus) TvFocus.container else rest,
            focusedContentColor = if (showFocus) TvFocus.content else restContent,
            pressedContainerColor = if (showFocus) TvFocus.container else rest,
            pressedContentColor = if (showFocus) TvFocus.content else restContent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        interactionSource = interactions,
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = SECTION_PILL_PADDING),
            horizontalArrangement = Arrangement.spacedBy(SECTION_PILL_ICON_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(SECTION_PILL_ICON.scaledLike(SECTION_PILL_LABEL)),
            )
            Text(label, style = SECTION_PILL_LABEL, maxLines = 1)
        }
    }
}

internal data class TvDetailFact(
    val label: String,
    val value: String,
    val mono: Boolean = false,
    val styled: AnnotatedString? = null,
)

@Composable
internal fun TvDetailTile(
    @DrawableRes glyph: Int?,
    modifier: Modifier = Modifier,
    image: (@Composable BoxScope.() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .size(DETAIL_TILE_WIDTH, DETAIL_TILE_HEIGHT)
            .clip(RoundedCornerShape(DETAIL_TILE_RADIUS))
            .background(PutioDesignTokens.componentBgActive),
        contentAlignment = Alignment.Center,
    ) {
        if (glyph != null) {
            Icon(
                painter = painterResource(glyph),
                contentDescription = null,
                tint = tvAccent(focused = false),
                modifier = Modifier.size(DETAIL_TILE_GLYPH),
            )
        }
        image?.invoke(this)
    }
}

@Composable
internal fun ColumnScope.TvDetailBody(
    tile: @Composable () -> Unit,
    title: String,
    key: Any = title,
    meta: AnnotatedString?,
    facts: List<TvDetailFact>,
    rows: (@Composable (firstRow: Modifier) -> Unit)?,
) {
    val scroll = remember(key) { ScrollState(0) }
    val scope = rememberCoroutineScope()
    val firstRow = Modifier.onFocusChanged { if (it.isFocused) scope.launch { scroll.animateScrollTo(0) } }
    val peek = remember { TvDetailPeek() }
    val shownFacts = facts.take(MAX_DETAIL_FACTS)
    Row(
        horizontalArrangement = Arrangement.spacedBy(DETAIL_TILE_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tile()
        Column(modifier = Modifier.weight(1f)) {
            PutioMiddleCutText(
                text = title,
                style = DETAIL_TITLE,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            if (meta != null) {
                Text(
                    text = meta,
                    style = PutioTypeScale.tvMeta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = DETAIL_META_GAP),
                )
            }
        }
    }
    if (shownFacts.isEmpty() && rows == null) return
    Box(
        modifier = Modifier
            .padding(top = DETAIL_BODY_GAP)
            .bleedHorizontally(DETAIL_SECONDARY_INSET)
            .weight(1f, fill = false)
            .peekNextRow(peek)
            .scrollFadeEdges(scroll)
            .verticalScroll(scroll),
    ) {
        TvDetailScrollContent(peek, hasFacts = shownFacts.isNotEmpty()) {
            if (shownFacts.isNotEmpty()) TvDetailFacts(shownFacts)
            rows?.invoke(firstRow)
        }
    }
}

@Composable
private fun TvDetailFacts(facts: List<TvDetailFact>) {
    Column(
        modifier = Modifier.padding(horizontal = DETAIL_SECONDARY_INSET),
        verticalArrangement = Arrangement.spacedBy(DETAIL_FACT_SPACING),
    ) {
        val labelWidth = DETAIL_FACT_LABEL_WIDTH.scaledLike(DETAIL_FACT)
        facts.forEach { fact ->
            Row(modifier = Modifier.semantics(mergeDescendants = true) {}) {
                Text(
                    text = fact.label,
                    style = DETAIL_FACT,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .width(labelWidth)
                        .alignByBaseline(),
                )
                Text(
                    text = fact.styled ?: AnnotatedString(fact.value),
                    style = if (fact.mono) DETAIL_FACT_MONO else DETAIL_FACT,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .weight(1f)
                        .alignByBaseline(),
                )
            }
        }
    }
}

@Composable
private fun TvDetailScrollContent(peek: TvDetailPeek, hasFacts: Boolean, content: @Composable () -> Unit) {
    Layout(content) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        val gap = if (hasFacts) DETAIL_BODY_GAP.roundToPx() else 0
        val tops = IntArray(placeables.size)
        var y = 0
        placeables.forEachIndexed { index, placeable ->
            tops[index] = y
            y += placeable.height + if (index == 0) gap else 0
        }
        val height = if (placeables.isEmpty()) 0 else y - if (placeables.size == 1) gap else 0
        peek.rows = placeables.indices
            .filter { !hasFacts || it > 0 }
            .map { tops[it] until tops[it] + placeables[it].height }
        layout(constraints.maxWidth, height) {
            placeables.forEachIndexed { index, placeable -> placeable.place(0, tops[index]) }
        }
    }
}

private class TvDetailPeek {
    var viewport by mutableIntStateOf(Constraints.Infinity)
    var rows: List<IntRange> = emptyList()

    fun cut(available: Int, bleed: Int, minRowHeight: Int, legible: Int, shownOfRow: Int): Int {
        val fullRows = rows.filter { it.last - it.first + 1 >= minRowHeight }
        val next = fullRows.indexOfFirst { it.last >= available }
        val intoPadding = available + bleed
        return when {
            next < 0 -> available
            next == 0 || intoPadding - fullRows[next].first >= legible -> intoPadding
            else -> fullRows[next - 1].first + shownOfRow
        }
    }
}

private fun Modifier.peekNextRow(peek: TvDetailPeek): Modifier = layout { measurable, constraints ->
    val available = constraints.maxHeight
    val placeable = measurable.measure(constraints.copy(maxHeight = minOf(peek.viewport, available + bleedPx())))
    val cut = peek.cut(
        available,
        bleed = bleedPx(),
        minRowHeight = DETAIL_PEEK_MIN_ROW.roundToPx(),
        legible = DETAIL_PEEK_LEGIBLE.roundToPx(),
        shownOfRow = DETAIL_PEEK_SHOWN.roundToPx(),
    )
    if (cut != peek.viewport) peek.viewport = cut
    layout(placeable.width, minOf(placeable.height, available)) { placeable.place(0, 0) }
}

private fun Density.bleedPx(): Int = DETAIL_PANEL_PADDING.roundToPx()

@Composable
internal fun TvDetailPrimarySkeleton() {
    Box(
        modifier = Modifier
            .padding(horizontal = DETAIL_SECONDARY_INSET)
            .fillMaxWidth()
            .height(DETAIL_PEEK_MIN_ROW)
            .background(PutioDesignTokens.componentBgActive, RoundedCornerShape(PutioRadius.medium)),
    )
}

@Composable
internal fun TvDetailDivider() {
    Spacer(
        Modifier
            .padding(horizontal = DETAIL_SECONDARY_INSET, vertical = DETAIL_DIVIDER_GAP)
            .fillMaxWidth()
            .height(DETAIL_DIVIDER)
            .background(MaterialTheme.colorScheme.borderVariant),
    )
}

private fun Modifier.scrollFadeEdges(scroll: ScrollState): Modifier =
    graphicsLayer {
        compositingStrategy = if (scroll.maxValue > 0) CompositingStrategy.Offscreen else CompositingStrategy.Auto
    }
        .drawWithContent {
            drawContent()
            val fade = DETAIL_SCROLL_FADE.toPx()
            if (scroll.value > 0) {
                drawRect(
                    Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = 0f, endY = fade),
                    size = Size(size.width, fade),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (scroll.canScrollForward) {
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Black,
                        1f to Color.Transparent,
                        startY = size.height - fade,
                        endY = size.height,
                    ),
                    topLeft = Offset(0f, size.height - fade),
                    size = Size(size.width, fade),
                    blendMode = BlendMode.DstIn,
                )
            }
        }

private fun Modifier.bleedHorizontally(bleed: Dp): Modifier = layout { measurable, constraints ->
    val bleedPx = bleed.roundToPx()
    val placeable = measurable.measure(constraints.offset(horizontal = 2 * bleedPx))
    layout(constraints.constrainWidth(placeable.width - 2 * bleedPx), placeable.height) {
        placeable.place(-bleedPx, 0)
    }
}

internal val TV_MONO_RUN: SpanStyle = SpanStyle(
    fontFamily = PutioTypeScale.tvMono.fontFamily,
    fontSize = MONO_RUN_SCALE.em,
    letterSpacing = MONO_RUN_TRACKING.em,
    fontFeatureSettings = PutioTypeScale.tvMono.fontFeatureSettings,
)

internal fun String.asMonoRun(): AnnotatedString =
    buildAnnotatedString { withStyle(TV_MONO_RUN) { append(this@asMonoRun) } }

internal fun monoNumber(label: String, number: String): AnnotatedString =
    buildAnnotatedString {
        append(label)
        val start = label.indexOf(number)
        if (start >= 0) {
            addStyle(TV_MONO_RUN, start, start + number.length)
        }
    }

internal fun TextStyle.asMonoRun(): TextStyle = copy(
    fontFamily = PutioTypeScale.tvMono.fontFamily,
    fontSize = fontSize * MONO_RUN_SCALE,
    letterSpacing = MONO_RUN_TRACKING.em,
    fontFeatureSettings = PutioTypeScale.tvMono.fontFeatureSettings,
)

@Composable
@ReadOnlyComposable
internal fun Dp.scaledLike(text: TextStyle): Dp =
    this * with(LocalDensity.current) { text.fontSize.toDp() / text.fontSize.value.dp }

internal val TV_LIST_WIDTH = 544.dp

internal val ROW_BLEED = 12.dp

internal val TV_LIST_ROW_HEIGHT = 48.dp

private val DETAIL_GAP = 8.dp
private val DETAIL_PANEL_PADDING = 20.dp
private val SECTION_PILL_HEIGHT = 24.dp
private val SECTION_LINE_GAP = 4.dp
private val SECTION_LABEL_GAP = 8.dp
private val SECTION_PILL_PADDING = 10.dp
private val SECTION_PILL_ICON_GAP = 5.dp
private val SECTION_PILL_ICON = 12.dp
private val SECTION_PILL_LABEL = PutioTypeScale.tvButton.copy(fontSize = 12.sp, lineHeight = 16.sp)
private val DETAIL_TITLE = PutioTypeScale.tvDetailTitle.copy(lineHeight = 22.sp)
private val DETAIL_META_GAP = 3.dp
private val DETAIL_BODY_GAP = 14.dp
private val DETAIL_SCROLL_FADE = 24.dp
private val DETAIL_TILE_WIDTH = 80.dp
private val DETAIL_TILE_HEIGHT = 45.dp
private val DETAIL_TILE_GAP = 12.dp
private val DETAIL_TILE_RADIUS = PutioRadius.small
private val DETAIL_TILE_GLYPH = 20.dp
private val DETAIL_PEEK_MIN_ROW = 40.dp
private val DETAIL_PEEK_LEGIBLE = 22.dp
private val DETAIL_PEEK_SHOWN = 34.dp
private val DETAIL_SECONDARY_INSET = 12.dp
private val DETAIL_DIVIDER = 1.dp
private val DETAIL_DIVIDER_GAP = 6.dp
private val DETAIL_FACT_SPACING = 5.5.dp
private val DETAIL_FACT = PutioTypeScale.tvMeta.copy(lineHeight = 17.4.sp).withFullLineBox()
private val DETAIL_FACT_MONO = DETAIL_FACT.asMonoRun()
private val DETAIL_FACT_LABEL_WIDTH = 70.dp
private const val MAX_DETAIL_FACTS = 3
private const val MONO_RUN_SCALE = 0.92f
private const val MONO_RUN_TRACKING = -0.01f
