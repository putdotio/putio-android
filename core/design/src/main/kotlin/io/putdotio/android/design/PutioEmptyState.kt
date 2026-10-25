package io.putdotio.android.design

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private val GLYPH_GAP = 20.dp
private val HEADLINE_GAP = 8.dp
private val ACTION_GAP = 24.dp
private val MESSAGE_WIDTH = 300.dp

/**
 * The put.io empty-state voice: a mono glyph, a one-line headline, one sentence and at most one
 * action, with no artwork. The glyph is decoration, so screen readers hear the headline first.
 * A state the table has no glyph for passes none. The caller owns size, scrolling and padding.
 */
@Composable
public fun PutioEmptyState(
    glyph: PutioEmptyGlyph?,
    headline: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    PutioEmptyState(glyph, headline, AnnotatedString(message), modifier, action)
}

/** The same state with a message that styles part of itself, such as the search term it names. */
@Composable
public fun PutioEmptyState(
    glyph: PutioEmptyGlyph?,
    headline: String,
    message: AnnotatedString,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (glyph != null) {
            Text(
                text = glyph.text,
                style = PutioTypeScale.mono.copy(
                    fontSize = PutioTypeScale.headlineLg.fontSize,
                    lineHeight = PutioTypeScale.headlineLg.lineHeight,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.clearAndSetSemantics {},
            )
            Spacer(Modifier.height(GLYPH_GAP))
        }
        Text(
            text = headline,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(HEADLINE_GAP))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = MESSAGE_WIDTH),
        )
        if (action != null) {
            Spacer(Modifier.height(ACTION_GAP))
            action()
        }
    }
}
