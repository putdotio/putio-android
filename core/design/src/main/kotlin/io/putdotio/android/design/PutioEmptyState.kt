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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** The mono glyphs of DESIGN.md's Empty and error states table. */
public enum class PutioEmptyGlyph(internal val text: String) {
    EmptyFolder("[ ]"),
    NoResults("¯\\_(ツ)_/¯"),
    NoTransfers("( ⌐■_■)"),
    Offline("(x_x)"),
}

private val GlyphGap = 20.dp
private val HeadlineGap = 8.dp
private val ActionGap = 24.dp
private val MessageWidth = 300.dp

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
            Spacer(Modifier.height(GlyphGap))
        }
        Text(
            text = headline,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(HeadlineGap))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = MessageWidth),
        )
        if (action != null) {
            Spacer(Modifier.height(ActionGap))
            action()
        }
    }
}
