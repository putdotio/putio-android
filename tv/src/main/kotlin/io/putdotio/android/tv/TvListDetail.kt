package io.putdotio.android.tv

import androidx.annotation.DrawableRes
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioTypeScale

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TvListDetail(
    list: @Composable ColumnScope.() -> Unit,
    detail: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    detailFocus: FocusRequester = FocusRequester.Default,
    listFocus: FocusRequester = FocusRequester.Default,
    onDetailFocusChanged: (Boolean) -> Unit = {},
    detailTop: Dp = SECTION_LINE_HEIGHT + DETAIL_TOP_NUDGE,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .offset(x = -ROW_BLEED)
                .width(TV_LIST_WIDTH)
                .fillMaxHeight(),
            content = list,
        )
        Spacer(Modifier.width(DETAIL_GAP))
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(top = detailTop)
                .onFocusChanged { onDetailFocusChanged(it.hasFocus) }
                .focusProperties {
                    enter = { detailFocus }
                    left = listFocus
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
            .padding(start = ROW_BLEED, end = ROW_BLEED, bottom = SECTION_LINE_GAP)
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
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(SECTION_PILL_ICON))
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
internal fun ColumnScope.TvDetailBody(
    title: String,
    meta: AnnotatedString?,
    facts: List<TvDetailFact>,
    actions: @Composable RowScope.() -> Unit,
) {
    Text(
        text = remember(title) { title.breakableAtDots() },
        style = DETAIL_TITLE,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = DETAIL_TITLE_MAX_LINES,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clearAndSetSemantics {
            heading()
            text = AnnotatedString(title)
        },
    )
    if (meta != null) {
        Text(
            text = meta,
            style = PutioTypeScale.tvMeta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = DETAIL_META_GAP),
        )
    }
    Row(
        modifier = Modifier.padding(top = DETAIL_ACTIONS_GAP),
        horizontalArrangement = Arrangement.spacedBy(DETAIL_ACTION_SPACING),
        verticalAlignment = Alignment.CenterVertically,
        content = actions,
    )
    Column(
        modifier = Modifier.padding(top = DETAIL_FACTS_GAP),
        verticalArrangement = Arrangement.spacedBy(DETAIL_FACT_SPACING),
    ) {
        facts.take(MAX_DETAIL_FACTS).forEach { fact ->
            Row(modifier = Modifier.semantics(mergeDescendants = true) {}) {
                Text(
                    text = fact.label,
                    style = DETAIL_FACT,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .width(DETAIL_FACT_LABEL_WIDTH)
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
internal fun TvDetailButton(
    label: AnnotatedString?,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    detailHasFocus: Boolean = false,
    enabled: Boolean = true,
    danger: Boolean = false,
    looksFocused: Boolean = false,
) {
    TvButton(
        onClick = onClick,
        modifier = if (label == null) modifier.width(TvButtonSize.DetailIcon.height) else modifier,
        enabled = enabled,
        looksFocused = looksFocused,
        style = when {
            danger -> TvButtonStyle.TonalDanger
            primary && detailHasFocus -> TvButtonStyle.Filled
            else -> TvButtonStyle.Tonal
        },
        size = if (label == null) TvButtonSize.DetailIcon else TvButtonSize.Detail,
    ) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(TvButtonSize.Detail.icon))
        if (label != null) Text(label, style = DETAIL_BUTTON_LABEL, maxLines = 1, softWrap = false)
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

private fun String.breakableAtDots(): String = replace(".", ".​").removeSuffix("​")

internal val TV_LIST_WIDTH = 544.dp

internal val ROW_BLEED = 12.dp

internal val TV_LIST_ROW_HEIGHT = 48.dp

private val DETAIL_GAP = 24.dp
private val SECTION_PILL_HEIGHT = 24.dp
private val SECTION_LINE_GAP = 4.dp
private val SECTION_LINE_HEIGHT = SECTION_PILL_HEIGHT + SECTION_LINE_GAP
private val DETAIL_TOP_NUDGE = 2.dp
private val SECTION_LABEL_GAP = 8.dp
private val SECTION_PILL_PADDING = 10.dp
private val SECTION_PILL_ICON_GAP = 5.dp
private val SECTION_PILL_ICON = 12.dp
private val SECTION_PILL_LABEL = PutioTypeScale.tvButton.copy(fontSize = 11.sp, lineHeight = 16.sp)
private val DETAIL_TITLE = PutioTypeScale.tvDetailTitle.copy(lineHeight = 22.sp)
private const val DETAIL_TITLE_MAX_LINES = 5
private val DETAIL_META_GAP = 5.dp
private val DETAIL_ACTIONS_GAP = 16.dp
private val DETAIL_ACTION_SPACING = 4.dp
private val DETAIL_FACTS_GAP = 17.5.dp
private val DETAIL_FACT_SPACING = 5.5.dp
private val DETAIL_FACT = PutioTypeScale.tvMeta.copy(lineHeight = 17.4.sp).withFullLineBox()
private val DETAIL_FACT_MONO = DETAIL_FACT.asMonoRun()
private val DETAIL_FACT_LABEL_WIDTH = 70.dp
private val DETAIL_BUTTON_LABEL = PutioTypeScale.tvButton.copy(fontSize = 12.sp)
private const val MAX_DETAIL_FACTS = 3
private const val MONO_RUN_SCALE = 0.92f
private const val MONO_RUN_TRACKING = -0.01f
