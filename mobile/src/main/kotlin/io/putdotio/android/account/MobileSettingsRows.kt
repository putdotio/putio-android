package io.putdotio.android.account

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.putdotio.android.design.PutioRadius
import io.putdotio.android.design.PutioSwitch
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.design.putioMono

/** A settings group's place in its segmented column: the ends round to 16dp, the joins to 4dp. */
internal enum class SettingsSegment { Only, First, Middle, Last }

internal fun settingsSegment(index: Int, count: Int): SettingsSegment =
    when {
        count == 1 -> SettingsSegment.Only
        index == 0 -> SettingsSegment.First
        index == count - 1 -> SettingsSegment.Last
        else -> SettingsSegment.Middle
    }

private fun SettingsSegment.shape(): RoundedCornerShape {
    val outer = PutioRadius.large
    val inner = PutioRadius.extraSmall
    return when (this) {
        SettingsSegment.Only -> RoundedCornerShape(outer)
        SettingsSegment.First -> RoundedCornerShape(outer, outer, inner, inner)
        SettingsSegment.Middle -> RoundedCornerShape(inner)
        SettingsSegment.Last -> RoundedCornerShape(inner, inner, outer, outer)
    }
}

/** DESIGN.md's `label-md` group label over a segmented group. */
@Composable
internal fun MobileSettingsLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 32.dp, end = 32.dp, top = 20.dp, bottom = 8.dp)
            .semantics { heading() },
    )
}

/** A segmented group: rows one step above the page, `hairline` (2dp) apart. */
@Composable
internal fun MobileSettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

/**
 * One segment of a group. [interaction] carries the row's click or toggle, so the whole segment is the
 * target; [below] sits under the line on the same surface, for a save failure.
 */
@Composable
internal fun MobileSettingsSegment(
    segment: SettingsSegment,
    modifier: Modifier = Modifier,
    interaction: Modifier = Modifier,
    below: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(segment.shape())
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(interaction.fillMaxWidth(), content = content)
        below?.let { Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), content = it) }
    }
}

/** What a one-line row shows at its end. */
internal sealed interface SettingsTrailing {
    data object None : SettingsTrailing

    /** [color] defaults to `on-surface-variant`. */
    data class Value(val text: AnnotatedString, val color: Color? = null) : SettingsTrailing

    data class Switch(val checked: Boolean, val enabled: Boolean) : SettingsTrailing
}

/** Plain text in a value slot; [mono] sets the whole value in Berkeley Mono, for a number. */
@Composable
internal fun settingsValue(text: String, mono: Boolean = false, color: Color? = null): SettingsTrailing.Value =
    SettingsTrailing.Value(
        if (mono) {
            AnnotatedString(text, MaterialTheme.typography.bodySmall.putioMono().toSpanStyle())
        } else {
            AnnotatedString(text)
        },
        color,
    )

/**
 * DESIGN.md's settings row: a leading glyph, the title on one line and a trailing value or switch,
 * 56dp tall. Large text wraps rather than cuts.
 */
@Composable
internal fun MobileSettingsLine(
    title: String,
    @DrawableRes icon: Int?,
    trailing: SettingsTrailing,
    modifier: Modifier = Modifier,
    saving: Boolean = false,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    iconColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(painterResource(it), contentDescription = null, tint = iconColor, modifier = Modifier.size(22.dp))
        }
        TitleAndTrailing(
            title = {
                // body-md has no Material slot.
                Text(title, style = PutioTypeScale.bodyMd, color = titleColor)
            },
            trailing = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    when (trailing) {
                        SettingsTrailing.None -> Unit
                        is SettingsTrailing.Value -> Text(
                            text = trailing.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = trailing.color ?: MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                        )
                        is SettingsTrailing.Switch -> PutioSwitch(trailing.checked, enabled = trailing.enabled)
                    }
                }
            },
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The title takes what the trailing slot leaves; the trailing slot takes what it needs, up to 60% of the
 * line, so a long value wraps beside a readable title instead of squeezing it.
 */
@Composable
private fun TitleAndTrailing(
    title: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(contents = listOf(title, trailing), modifier = modifier) { (titles, trailings), constraints ->
        val gap = 16.dp.roundToPx()
        val width = constraints.maxWidth
        val trailingPlaceable = trailings.firstOrNull()?.measure(
            Constraints(maxWidth = (width * TRAILING_SHARE).toInt(), maxHeight = constraints.maxHeight),
        )
        val trailingWidth = trailingPlaceable?.width ?: 0
        val used = if (trailingWidth > 0) trailingWidth + gap else 0
        val titlePlaceable = titles.first().measure(
            Constraints(maxWidth = (width - used).coerceAtLeast(0), maxHeight = constraints.maxHeight),
        )
        val height = maxOf(titlePlaceable.height, trailingPlaceable?.height ?: 0, constraints.minHeight)
        layout(width, height) {
            titlePlaceable.placeRelative(0, (height - titlePlaceable.height) / 2)
            trailingPlaceable?.placeRelative(width - trailingWidth, (height - trailingPlaceable.height) / 2)
        }
    }
}

private const val TRAILING_SHARE = 0.6f
