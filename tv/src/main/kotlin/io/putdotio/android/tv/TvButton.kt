package io.putdotio.android.tv

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ButtonScale
import androidx.tv.material3.MaterialTheme
import io.putdotio.android.design.PutioDesignTokens

@Composable
internal fun TvButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: TvButtonStyle = TvButtonStyle.Tonal,
    size: TvButtonSize = TvButtonSize.Regular,
    interactions: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable RowScope.() -> Unit,
) {
    val focused by interactions.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    val (rest, restContent) = when (style) {
        TvButtonStyle.Tonal -> PutioDesignTokens.componentBgActive to colors.onSurface
        TvButtonStyle.Text -> Color.Transparent to colors.onSurface
        TvButtonStyle.Filled -> colors.primary to colors.onPrimary
        TvButtonStyle.Destructive -> colors.error to colors.onError
    }
    val keepsFill = style == TvButtonStyle.Filled || style == TvButtonStyle.Destructive
    val ring = TvFocus.container
    val gap = PutioDesignTokens.componentBgHover
    Button(
        onClick = onClick,
        modifier = modifier
            .height(size.height)
            .drawWithContent {
                drawContent()
                if (keepsFill && focused) {
                    drawPillOutline(gap, RING_GAP.toPx() / 2, RING_GAP.toPx())
                    drawPillOutline(ring, (RING_GAP + RING_WIDTH / 2).toPx(), RING_WIDTH.toPx())
                }
            },
        enabled = enabled,
        scale = ButtonScale.None,
        shape = ButtonDefaults.shape(CircleShape),
        colors = ButtonDefaults.colors(
            containerColor = rest,
            contentColor = restContent,
            focusedContainerColor = if (keepsFill) rest else TvFocus.container,
            focusedContentColor = if (keepsFill) restContent else TvFocus.content,
            pressedContainerColor = if (keepsFill) rest else TvFocus.container,
            pressedContentColor = if (keepsFill) restContent else TvFocus.content,
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
}

internal enum class TvButtonStyle {
    Tonal,
    Text,
    Filled,
    Destructive,
}

private val RING_GAP = 2.dp
private val RING_WIDTH = 2.dp
