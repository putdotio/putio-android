package io.putdotio.android.playback

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player as Media3Player
import androidx.media3.common.util.UnstableApi
import io.putdotio.android.R

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun RetainedPlayerPreferences.adoptPlaybackOptions(player: Media3Player) {
    playbackSpeed = player.playbackParameters.speed
    val parameters = player.trackSelectionParameters
    val audioOverride = parameters.overrides.values.singleOrNull { it.type == C.TRACK_TYPE_AUDIO }
    // A reprepare can temporarily expose no current tracks; the override still owns the chosen format.
    val identity = audioOverride?.let { selected ->
        selected.trackIndices.singleOrNull()?.let { index ->
            selected.mediaTrackGroup.getFormat(index).toAudioTrackIdentity()
        }
    }
    audioSelection = identity?.let { AudioSelection.Track(it) } ?: AudioSelection.Automatic
}

/** A choice row: its label, and a yellow check while it is the current one. */
@Composable
internal fun PlaybackOption(
    label: AnnotatedString,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .alpha(if (enabled) 1f else 0.38f)
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (selected) Icon(
            painterResource(R.drawable.ic_ph_check),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}
