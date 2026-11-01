package io.putdotio.android.tv

import androidx.annotation.DrawableRes
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
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

@Composable
internal fun ColumnScope.TvSheetRows(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), content = content)
}

@Composable
internal fun TvSheetLabel(text: String) {
    Text(
        text = text,
        style = PutioTypeScale.tvLabel.copy(lineHeight = DETAIL_LINE),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp),
    )
}

@Composable
internal fun TvSheetOption(
    label: String,
    detail: AnnotatedString?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes leadingIcon: Int? = null,
    @DrawableRes trailingIcon: Int? = null,
    role: Role = Role.RadioButton,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = OPTION_MIN_HEIGHT)
            .semantics {
                this.role = role
                if (role == Role.RadioButton) this.selected = selected
            },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(PutioRadius.medium)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) PutioDesignTokens.componentBgActive else Color.Transparent,
            contentColor = if (danger) PutioDesignTokens.redTextSecondary else colors.onSurface,
            focusedContainerColor = TvFocus.container,
            focusedContentColor = TvFocus.content,
            pressedContainerColor = TvFocus.container,
            pressedContentColor = TvFocus.content,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = colors.onSurfaceVariant,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        interactionSource = interactions,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = OPTION_MIN_HEIGHT)
                .padding(start = 12.dp, top = 5.dp, end = 12.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(LEADING_SLOT), contentAlignment = Alignment.Center) {
                val icon = leadingIcon ?: R.drawable.ic_ph_check.takeIf { selected }
                if (icon != null) {
                    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(label, style = OPTION_LABEL)
                if (detail != null) {
                    Text(
                        detail,
                        style = PutioTypeScale.tvMeta.copy(lineHeight = DETAIL_LINE),
                        color = if (focused) TvFocus.content else colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
            TrailingIcon(trailingIcon, focused)
        }
    }
}

@Composable
private fun RowScope.TrailingIcon(@DrawableRes icon: Int?, focused: Boolean) {
    if (icon == null) return
    Icon(
        painterResource(icon),
        contentDescription = null,
        tint = if (focused) TvFocus.content else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(16.dp).align(Alignment.CenterVertically),
    )
}

private val OPTION_MIN_HEIGHT = 40.dp
private val OPTION_LABEL = PutioTypeScale.tvBody.copy(fontSize = 15.sp, lineHeight = 18.sp)
private val DETAIL_LINE = 15.sp
private val LEADING_SLOT = 20.dp
