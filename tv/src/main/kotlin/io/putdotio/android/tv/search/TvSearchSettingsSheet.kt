package io.putdotio.android.tv.search

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import io.putdotio.android.R
import io.putdotio.android.search.RecentSearchEdit
import io.putdotio.android.tv.TvSheetOption
import io.putdotio.android.tv.TvSheetRows
import io.putdotio.android.tv.TvSideSheet
import io.putdotio.android.tv.TvSwitch
import io.putdotio.android.tv.files.FILES_SHEET_SCRIM

@Composable
internal fun TvSearchSettingsSheet(
    enabled: Boolean,
    canClear: Boolean,
    onEdit: (RecentSearchEdit) -> Unit,
    onDismiss: () -> Unit,
    returnFocus: FocusRequester?,
) {
    TvSideSheet(
        onDismiss = onDismiss,
        returnFocus = returnFocus,
        title = stringResource(R.string.tv_search_settings_title),
        scrim = FILES_SHEET_SCRIM,
    ) { initialFocus ->
        TvSheetRows {
            TvSheetOption(
                label = stringResource(R.string.tv_search_history_show),
                detail = null,
                selected = false,
                onClick = { onEdit(RecentSearchEdit.SetEnabled(!enabled)) },
                leadingIcon = R.drawable.ic_ph_clock_counter_clockwise,
                role = Role.Switch,
                modifier = Modifier
                    .focusRequester(initialFocus)
                    .semantics { toggleableState = ToggleableState(enabled) },
                trailing = { focused -> TvSwitch(checked = enabled, focused = focused) },
            )
            if (enabled) {
                TvSheetOption(
                    label = stringResource(R.string.tv_search_history_clear),
                    detail = null,
                    selected = false,
                    onClick = {
                        onEdit(RecentSearchEdit.Clear)
                        onDismiss()
                    },
                    leadingIcon = R.drawable.ic_ph_trash,
                    role = Role.Button,
                    enabled = canClear,
                )
            }
        }
    }
}
