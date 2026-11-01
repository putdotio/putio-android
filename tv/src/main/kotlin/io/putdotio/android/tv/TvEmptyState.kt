package io.putdotio.android.tv

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.BaselineShift
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
    glyphRunPerCharacter: Boolean = false,
    supporting: (@Composable () -> Unit)? = null,
    messageColor: Color = Color.Unspecified,
    glyphModifier: Modifier = Modifier,
) {
    LaunchedEffect(action != null, action?.enabled, claimFocus) {
        if (action == null || !action.enabled || !claimFocus) return@LaunchedEffect
        withFrameNanos {}
        actionFocus.requestFocus()
    }
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        if (glyph != null) {
            val glyphStyle = PutioTypeScale.mono.copy(
                fontSize = PutioTypeScale.headlineLg.fontSize,
                lineHeight = GLYPH_LINE,
            ).withFullLineBox()
            val glyphText = if (glyphRunPerCharacter) {
                glyph.text.runPerCharacter(glyphStyle)
            } else {
                AnnotatedString(glyph.text)
            }
            Text(
                text = glyphText,
                style = glyphStyle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
                modifier = glyphModifier
                    .height(with(LocalDensity.current) { GLYPH_LINE.toDp() })
                    .wrapContentHeight(unbounded = true)
                    .clearAndSetSemantics {},
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
                color = messageColor.takeOrElse { MaterialTheme.colorScheme.onSurfaceVariant },
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = MESSAGE_WIDTH),
            )
        }
        supporting?.invoke()
        if (action != null) {
            TvButton(
                onClick = action.onClick,
                modifier = Modifier.padding(top = ACTION_GAP).focusRequester(actionFocus),
                enabled = action.enabled,
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

private fun String.runPerCharacter(style: TextStyle): AnnotatedString = buildAnnotatedString {
    val text = this@runPerCharacter
    var index = 0
    while (index < text.length) {
        val next = text.offsetByCodePoints(index, 1)
        val runStyle = if (text[index] in FULL_WIDTH_PUNCTUATION) {
            style.fullWidthPunctuationRun()
        } else {
            SpanStyle(fontFamily = style.fontFamily)
        }
        withStyle(runStyle) { append(text, index, next) }
        index = next
    }
}

private fun TextStyle.fullWidthPunctuationRun(): SpanStyle = SpanStyle(
    fontFamily = fontFamily,
    fontSize = fontSize * FULL_WIDTH_SCALE,
    letterSpacing = fontSize * (1f - FULL_WIDTH_SCALE),
    baselineShift = BaselineShift(FULL_WIDTH_RAISE),
)

private val FULL_WIDTH_PUNCTUATION = '\u3000'..'\u30FF'
private const val FULL_WIDTH_SCALE = 0.75f
private const val FULL_WIDTH_RAISE = 0.1f

internal data class TvEmptyStateAction(
    val label: String,
    val onClick: () -> Unit,
    @param:DrawableRes val icon: Int? = null,
    val enabled: Boolean = true,
)

private val MESSAGE_WIDTH = 440.dp
private val ACTION_GAP = 10.dp
private val HEADLINE_TOP = 4.dp
private val GLYPH_LINE = 32.sp
private val HEADLINE_LINE = 22.sp
