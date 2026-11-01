package io.putdotio.android.tv

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ButtonScale
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ProvideTextStyle
import androidx.tv.material3.Surface
import io.putdotio.android.design.PutioDesignTokens

@Composable
internal fun TvButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: TvButtonStyle = TvButtonStyle.Tonal,
    size: TvButtonSize = TvButtonSize.Regular,
    interactions: MutableInteractionSource = remember { MutableInteractionSource() },
    hugsLabel: Boolean = false,
    looksFocused: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val focused by interactions.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    val (styleRest, styleRestContent) = when (style) {
        TvButtonStyle.Tonal -> PutioDesignTokens.componentBgActive to colors.onSurface
        TvButtonStyle.TonalDanger -> PutioDesignTokens.componentBgActive to PutioDesignTokens.redTextSecondary
        TvButtonStyle.Text -> Color.Transparent to colors.onSurface
        TvButtonStyle.Filled -> colors.primary to colors.onPrimary
        TvButtonStyle.Destructive -> colors.error to colors.onError
    }
    val (rest, restContent) = if (looksFocused) TvFocus.container to TvFocus.content else styleRest to styleRestContent
    val keepsFill = style == TvButtonStyle.Filled || style == TvButtonStyle.Destructive
    val ring = TvFocus.container
    val gap = PutioDesignTokens.componentBgHover
    val decorated = modifier
        .height(size.height)
        .drawWithContent {
            drawContent()
            if (keepsFill && focused) {
                drawPillOutline(gap, RING_GAP.toPx() / 2, RING_GAP.toPx())
                drawPillOutline(ring, (RING_GAP + RING_WIDTH / 2).toPx(), RING_WIDTH.toPx())
            }
        }
    val focusedFill = if (keepsFill) rest else TvFocus.container
    val focusedContent = if (keepsFill) restContent else TvFocus.content
    if (hugsLabel) {
        TvLabelButton(
            onClick = onClick,
            modifier = decorated,
            enabled = enabled,
            rest = rest to restContent,
            focused = focusedFill to focusedContent,
            size = size,
            interactions = interactions,
            content = content,
        )
        return
    }
    Button(
        onClick = onClick,
        modifier = decorated,
        enabled = enabled,
        scale = ButtonScale.None,
        shape = ButtonDefaults.shape(CircleShape),
        colors = ButtonDefaults.colors(
            containerColor = rest,
            contentColor = restContent,
            focusedContainerColor = focusedFill,
            focusedContentColor = focusedContent,
            pressedContainerColor = focusedFill,
            pressedContentColor = focusedContent,
        ),
        contentPadding = PaddingValues(horizontal = size.padding, vertical = 0.dp),
        interactionSource = interactions,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
private fun TvLabelButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    rest: Pair<Color, Color>,
    focused: Pair<Color, Color>,
    size: TvButtonSize,
    interactions: MutableInteractionSource,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.semantics { role = Role.Button },
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = rest.first,
            contentColor = rest.second,
            focusedContainerColor = focused.first,
            focusedContentColor = focused.second,
            pressedContainerColor = focused.first,
            pressedContentColor = focused.second,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        interactionSource = interactions,
    ) {
        ProvideTextStyle(MaterialTheme.typography.labelLarge) {
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(horizontal = size.padding),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

private fun DrawScope.drawPillOutline(color: Color, reach: Float, width: Float) {
    drawRoundRect(
        color = color,
        topLeft = Offset(-reach, -reach),
        size = Size(size.width + 2 * reach, size.height + 2 * reach),
        cornerRadius = CornerRadius(size.height / 2 + reach),
        style = Stroke(width),
    )
}

internal enum class TvButtonSize(
    val height: Dp,
    val padding: Dp,
    val icon: Dp,
) {
    Regular(height = 36.dp, padding = 18.dp, icon = 14.dp),
    Compact(height = 32.dp, padding = 14.dp, icon = 12.dp),

    Detail(height = 30.dp, padding = 11.dp, icon = 12.dp),

    DetailIcon(height = 30.dp, padding = 9.dp, icon = 12.dp),

    Snackbar(height = 28.dp, padding = 10.dp, icon = 12.dp),

    Header(height = 28.dp, padding = 18.dp, icon = 11.dp),
}

internal enum class TvButtonStyle {
    Tonal,
    TonalDanger,
    Text,
    Filled,
    Destructive,
}

private val RING_GAP = 2.dp
private val RING_WIDTH = 2.dp
