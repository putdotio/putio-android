package io.putdotio.android.tv

import androidx.annotation.DrawableRes
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.putdotio.android.design.PutioRadius
import io.putdotio.android.design.PutioTypeScale

@Composable
internal fun TvListRow(
    @DrawableRes glyph: Int,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    @DrawableRes trailingIcon: Int? = null,
    showsFocus: Boolean = true,
    meta: @Composable (Color) -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused = interactions.collectIsFocusedAsState().value && showsFocus
    val colors = MaterialTheme.colorScheme
    val secondary = if (focused) TvFocus.secondaryContent else colors.onSurfaceVariant
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .fillMaxWidth()
            .height(TV_LIST_ROW_HEIGHT),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(PutioRadius.medium)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = colors.onSurface,
            focusedContainerColor = if (showsFocus) TvFocus.container else Color.Transparent,
            focusedContentColor = if (showsFocus) TvFocus.content else colors.onSurface,
            pressedContainerColor = TvFocus.container,
            pressedContentColor = TvFocus.content,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        interactionSource = interactions,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = ROW_PADDING),
            horizontalArrangement = Arrangement.spacedBy(ROW_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(glyph),
                contentDescription = null,
                tint = tvAccent(focused),
                modifier = Modifier.size(ROW_GLYPH),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = ROW_TITLE, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(
                    modifier = Modifier.padding(top = 1.dp),
                    horizontalArrangement = Arrangement.spacedBy(ROW_META_GAP),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    meta(secondary)
                }
            }
            if (trailingIcon != null) {
                Icon(
                    painter = painterResource(trailingIcon),
                    contentDescription = null,
                    tint = secondary,
                    modifier = Modifier.size(ROW_CARET),
                )
            }
        }
    }
}

@Composable
internal fun TvListRowMetaParts(parts: List<@Composable () -> Unit>, color: Color) {
    parts.forEachIndexed { index, part ->
        if (index > 0) {
            Text("·", style = TV_LIST_ROW_META, color = color, modifier = Modifier.clearAndSetSemantics {})
        }
        part()
    }
}

internal val TV_LIST_ROW_META = PutioTypeScale.tvMeta.copy(lineHeight = 14.sp).withFullLineBox()
internal val TV_LIST_ROW_META_MONO = TV_LIST_ROW_META.asMonoRun()
internal val TV_LIST_ROW_META_ICON = 13.dp

private val ROW_PADDING = 12.dp
private val ROW_GAP = 16.dp
private val ROW_GLYPH = 20.dp
private val ROW_CARET = 16.dp
private val ROW_META_GAP = 5.dp
private val ROW_TITLE = PutioTypeScale.tvBody.copy(lineHeight = 16.sp).withFullLineBox()
