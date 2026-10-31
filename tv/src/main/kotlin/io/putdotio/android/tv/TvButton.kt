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
import androidx.compose.ui.graphics.drawscope.Stroke
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
    Button(
        onClick = onClick,
        modifier = modifier
            .height(BUTTON_HEIGHT)
            .drawWithContent {
                drawContent()
                if (keepsFill && focused) {
                    val reach = (RING_GAP + RING_WIDTH / 2).toPx()
                    drawRoundRect(
                        color = ring,
                        topLeft = Offset(-reach, -reach),
                        size = Size(size.width + 2 * reach, size.height + 2 * reach),
                        cornerRadius = CornerRadius(size.height / 2 + reach),
                        style = Stroke(RING_WIDTH.toPx()),
                    )
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
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp),
        interactionSource = interactions,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

internal enum class TvButtonStyle {
    Tonal,
    Text,
    Filled,
    Destructive,
}

private val BUTTON_HEIGHT = 36.dp
private val RING_GAP = 2.dp
private val RING_WIDTH = 2.dp
