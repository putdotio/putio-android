package io.putdotio.android.tv

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.putdotio.android.design.PutioEmptyGlyph
import io.putdotio.android.design.PutioTypeScale

@Composable
internal fun TvEmptyState(
    glyph: PutioEmptyGlyph?,
    headline: String,
    message: String?,
    modifier: Modifier = Modifier,
    action: TvEmptyStateAction? = null,
    actionFocus: FocusRequester = remember { FocusRequester() },
    claimFocus: Boolean = true,
) {
    LaunchedEffect(action != null, claimFocus) {
        if (action == null || !claimFocus) return@LaunchedEffect
        withFrameNanos {}
        actionFocus.requestFocus()
    }
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        if (glyph != null) {
            Text(
                text = glyph.text,
                style = PutioTypeScale.mono.copy(
                    fontSize = PutioTypeScale.headlineLg.fontSize,
                    lineHeight = GLYPH_LINE,
                ).withFullLineBox(),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
        Text(
            text = headline,
            style = MaterialTheme.typography.displaySmall.copy(lineHeight = HEADLINE_LINE).withFullLineBox(),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = HEADLINE_TOP).semantics { heading() },
        )
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall.withFullLineBox(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = MESSAGE_WIDTH),
            )
        }
        if (action != null) {
            TvButton(
                onClick = action.onClick,
                modifier = Modifier.padding(top = ACTION_GAP).focusRequester(actionFocus),
            ) {
                if (action.icon != null) {
                    Icon(
                        painter = painterResource(action.icon),
                        contentDescription = null,
                        modifier = Modifier.size(TvButtonSize.Regular.icon),
                    )
                }
                Text(action.label, style = PutioTypeScale.bodySm.copy(fontWeight = FontWeight.Medium))
            }
        }
    }
}

internal data class TvEmptyStateAction(
    val label: String,
    val onClick: () -> Unit,
    @param:DrawableRes val icon: Int? = null,
)

private val MESSAGE_WIDTH = 440.dp
private val ACTION_GAP = 10.dp
private val HEADLINE_TOP = 4.dp
private val GLYPH_LINE = 32.sp
private val HEADLINE_LINE = 22.sp
