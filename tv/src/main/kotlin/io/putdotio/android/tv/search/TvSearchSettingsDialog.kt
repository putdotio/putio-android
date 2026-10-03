package io.putdotio.android.tv.search

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.search.RecentSearchEdit
import io.putdotio.android.tv.TvButton
import io.putdotio.android.tv.TvDialog

/**
 * tv-native's search settings: turn recent searches off (which clears them) or back on,
 * and clear them while they are on. Focus lands on the toggle; Back dismisses.
 */
@Composable
internal fun TvSearchSettingsDialog(
    enabled: Boolean,
    canClear: Boolean,
    onEdit: (RecentSearchEdit) -> Unit,
    onDismiss: () -> Unit,
) {
    TvDialog(
        title = stringResource(R.string.tv_search_settings_title),
        message = null,
        onDismiss = onDismiss,
    ) { focus ->
        TvButton(
            onClick = { onEdit(RecentSearchEdit.SetEnabled(!enabled)) },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus),
        ) {
            TvSearchSettingsLabel(
                icon = R.drawable.ic_ph_clock_counter_clockwise,
                text = if (enabled) R.string.tv_search_history_disable else R.string.tv_search_history_enable,
            )
        }
        if (enabled && canClear) {
            TvButton(onClick = { onEdit(RecentSearchEdit.Clear) }, modifier = Modifier.fillMaxWidth()) {
                TvSearchSettingsLabel(icon = R.drawable.ic_ph_trash, text = R.string.tv_search_history_clear)
            }
        }
    }
}

@Composable
private fun TvSearchSettingsLabel(
    @DrawableRes icon: Int,
    @StringRes text: Int,
) {
    Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
    Spacer(Modifier.width(8.dp))
    Text(stringResource(text))
}
