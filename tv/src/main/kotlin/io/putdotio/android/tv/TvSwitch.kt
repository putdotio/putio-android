package io.putdotio.android.tv

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.tv.material3.MaterialTheme
import io.putdotio.android.design.PutioSwitch
import io.putdotio.android.design.PutioSwitchSize

@Composable
internal fun TvSwitch(
    checked: Boolean,
    focused: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    PutioSwitch(
        checked = checked,
        modifier = modifier,
        enabled = enabled,
        dimensions = PutioSwitchSize.Tv,
        onTrack = if (focused) TvFocus.content else colors.onSurface,
        surface = if (focused) TvFocus.container else colors.surface,
    )
}
