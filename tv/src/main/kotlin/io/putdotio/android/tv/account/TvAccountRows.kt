package io.putdotio.android.tv.account

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioRadius
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.tv.TvButton
import io.putdotio.android.tv.TvFocus
import io.putdotio.android.tv.TvSwitch

internal data class TvSheetChoice<T>(
    val value: T,
    val label: String,
    val description: String? = null,
)

internal enum class TvSegment { Only, First, Middle, Last }

internal fun segmentOf(index: Int, count: Int): TvSegment =
    when {
        count == 1 -> TvSegment.Only
        index == 0 -> TvSegment.First
        index == count - 1 -> TvSegment.Last
        else -> TvSegment.Middle
    }

private fun TvSegment.shape(): Shape {
    val outer = PutioRadius.large
    val inner = PutioRadius.extraSmall
    return when (this) {
        TvSegment.Only -> RoundedCornerShape(outer)
        TvSegment.First -> RoundedCornerShape(topStart = outer, topEnd = outer, bottomStart = inner, bottomEnd = inner)
        TvSegment.Middle -> RoundedCornerShape(inner)
        TvSegment.Last -> RoundedCornerShape(topStart = inner, topEnd = inner, bottomStart = outer, bottomEnd = outer)
    }
}

@Composable
internal fun TvGroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = TvAccountType.groupLabel,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
    )
}

@Composable
internal fun TvSegments(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SEGMENT_GAP),
        content = content,
    )
}

@Composable
internal fun TvAccountGroupRow(
    title: String,
    @DrawableRes icon: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: AnnotatedString? = null,
    danger: Boolean = false,
    caret: Boolean = true,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    val content = when {
        focused -> TvFocus.content
        danger -> PutioDesignTokens.redTextSecondary
        else -> colors.onSurface
    }
    val secondary = when {
        focused -> TvAccountColors.focusSecondary
        danger -> PutioDesignTokens.redTextSecondary
        else -> colors.onSurfaceVariant
    }
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .semantics { this.selected = selected },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(PutioRadius.medium)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) PutioDesignTokens.componentBgActive else Color.Transparent,
            contentColor = content,
            focusedContainerColor = TvFocus.container,
            focusedContentColor = content,
            pressedContainerColor = TvFocus.container,
            pressedContentColor = content,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        interactionSource = interactions,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(icon),
                    contentDescription = null,
                    tint = if (danger) content else secondary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                title,
                style = PutioTypeScale.tvBody,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (value != null) {
                Text(value, style = PutioTypeScale.tvMeta, color = secondary, maxLines = 1)
            }
            if (caret) {
                Icon(
                    painterResource(R.drawable.ic_ph_caret_right),
                    contentDescription = null,
                    tint = secondary,
                    modifier = Modifier.padding(start = 8.dp).size(15.dp),
                )
            }
        }
    }
}

@Composable
internal fun TvSettingRow(
    title: String,
    @DrawableRes icon: Int,
    segment: TvSegment,
    modifier: Modifier = Modifier,
    description: String? = null,
    onClick: (() -> Unit)? = null,
    focusable: Boolean = true,
    role: Role? = null,
    trailing: @Composable RowScope.(focused: Boolean) -> Unit = {},
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    val shape = segment.shape()
    val body: @Composable (Boolean) -> Unit = { isFocused ->
        val content = if (isFocused) TvFocus.content else colors.onSurface
        val secondary = if (isFocused) TvAccountColors.focusSecondary else colors.onSurfaceVariant
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ROW_HEIGHT)
                .padding(horizontal = 16.dp, vertical = if (description == null) 0.dp else 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = secondary, modifier = Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = PutioTypeScale.tvBody,
                    color = content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (description != null) {
                    Text(
                        description,
                        style = TvAccountType.description,
                        color = secondary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            trailing(isFocused)
        }
    }
    if (!focusable) {
        Box(
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, shape)
                .semantics(mergeDescendants = true) {},
        ) { body(false) }
        return
    }
    Surface(
        onClick = onClick ?: {},
        modifier = modifier
            .fillMaxWidth()
            .semantics { if (role != null) this.role = role },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = colors.onSurface,
            focusedContainerColor = TvFocus.container,
            focusedContentColor = TvFocus.content,
            pressedContainerColor = TvFocus.container,
            pressedContentColor = TvFocus.content,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        interactionSource = interactions,
    ) { body(focused) }
}

@Composable
internal fun TvSwitchSettingRow(
    title: String,
    @DrawableRes icon: Int,
    checked: Boolean,
    segment: TvSegment,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    TvSettingRow(
        title = title,
        icon = icon,
        segment = segment,
        description = description,
        onClick = { onToggle(!checked) },
        modifier = modifier.semantics { toggleableState = ToggleableState(checked) },
        role = Role.Switch,
    ) { focused -> TvSwitch(checked = checked, focused = focused) }
}

@Composable
internal fun RowScope.TvSettingValue(value: AnnotatedString, focused: Boolean) {
    Text(
        value,
        style = PutioTypeScale.tvMeta,
        color = if (focused) TvAccountColors.focusSecondary else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun TvAccountStatus(text: String) {
    Text(
        text = text,
        style = PutioTypeScale.tvBody,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
internal fun TvAccountNotice(
    text: String,
    action: String?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = text,
            style = TvAccountType.description,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            TvButton(onClick = onAction, modifier = modifier) { Text(action) }
        }
    }
}

internal object TvAccountColors {
    val focusSecondary: Color = Color(FOCUS_SECONDARY_ARGB)
}

internal object TvAccountType {
    val groupLabel: TextStyle = PutioTypeScale.tvLabel.copy(fontSize = 13.sp, lineHeight = 19.sp)
    val description: TextStyle = PutioTypeScale.tvMeta.copy(fontSize = 11.sp, lineHeight = 16.sp)
    val footnote: TextStyle = PutioTypeScale.tvMeta.copy(fontSize = 11.sp, lineHeight = 15.sp)
    val name: TextStyle =
        PutioTypeScale.tvBody.copy(fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    val initial: TextStyle =
        PutioTypeScale.tvBody.copy(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val storageTitle: TextStyle = PutioTypeScale.tvBody.copy(fontSize = 14.sp, lineHeight = 20.sp)
    val option: TextStyle = PutioTypeScale.tvBody.copy(fontSize = 15.sp, lineHeight = 18.sp)
    val optionDetail: TextStyle = PutioTypeScale.tvMeta.copy(lineHeight = 15.sp)
}

internal val ROW_HEIGHT: Dp = 40.dp
private val SEGMENT_GAP = 2.dp
private const val FOCUS_SECONDARY_ARGB = 0xFF4D4D4D
