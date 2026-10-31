package io.putdotio.android.tv

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.putdotio.android.design.PutioEmptyGlyph
import io.putdotio.android.design.PutioTypeScale

/**
 * The put.io empty-state voice on TV (DESIGN.md › TV states): a mono glyph, a `tv-detail-title`
 * headline, one `tv-body` sentence and at most one action, centred in the space it fills.
 * [claimFocus] is false while D-pad focus is elsewhere, so the action does not pull it back.
 */
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
                    lineHeight = PutioTypeScale.headlineLg.lineHeight,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
        Text(
            text = headline,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = MESSAGE_WIDTH),
            )
        }
        if (action != null) {
            Spacer(Modifier.height(ACTION_GAP))
            TvButton(onClick = action.onClick, modifier = Modifier.focusRequester(actionFocus)) {
                if (action.icon != null) {
                    Icon(
                        painter = painterResource(action.icon),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(action.label)
            }
        }
    }
}

/** A [TvEmptyState]'s one action: a tonal button that takes focus while the pane holds it. */
internal data class TvEmptyStateAction(
    val label: String,
    val onClick: () -> Unit,
    @param:DrawableRes val icon: Int? = null,
)

private val MESSAGE_WIDTH = 440.dp
private val ACTION_GAP = 10.dp
